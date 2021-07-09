package ch.admin.bag.covidcertificate.backend.transformation.ws.client;

import ch.admin.bag.covidcertificate.backend.transformation.model.HCertPayload;
import ch.admin.bag.covidcertificate.backend.transformation.model.VerificationResponse;
import ch.admin.bag.covidcertificate.backend.transformation.ws.client.deserializer.CustomCovidCertificateDeserializer;
import ch.admin.bag.covidcertificate.backend.transformation.ws.client.exceptions.ResponseParseError;
import ch.admin.bag.covidcertificate.backend.transformation.ws.client.exceptions.ValidationException;
import ch.admin.bag.covidcertificate.sdk.core.models.healthcert.CovidCertificate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

public class VerificationCheckClient {

    private static final Logger logger = LoggerFactory.getLogger(VerificationCheckClient.class);

    private final String baseurl;
    private final String verifyEndpoint;
    private final RestTemplate rt;
    private final ObjectMapper objectMapper;

    public VerificationCheckClient(String baseurl, String verifyEndpoint, RestTemplate rt) {
        this.baseurl = baseurl;
        this.verifyEndpoint = verifyEndpoint;
        this.rt = rt;

        objectMapper =
                new ObjectMapper()
                        .registerModule(new KotlinModule())
                        .registerModule(new JavaTimeModule())
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        var deserialization = new SimpleModule();
        deserialization.addDeserializer(
                CovidCertificate.class, new CustomCovidCertificateDeserializer());
        objectMapper.registerModule(deserialization);
    }

    /**
     * Decode and verify a client HCert
     *
     * @param hCertPayload payload as sent with the original request
     * @return the decoded certificate if it can be decoded and is valid, null if it can't be
     *     decoded
     * @throws ValidationException certificate isn't valid
     * @throws ResponseParseError response from validation endpoint couldn't be parsed
     */
    public VerificationResponse validate(HCertPayload hCertPayload)
            throws ValidationException, ResponseParseError {
        final var verificationResponse = verify(hCertPayload);
        if (verificationResponse != null && verificationResponse.getSuccessState() != null) {
            return verificationResponse;
        } else if (verificationResponse == null) {
            throw new ResponseParseError(null);
        } else {
            throw new ValidationException(
                    verificationResponse.getErrorState() != null
                            ? verificationResponse.getErrorState()
                            : verificationResponse.getInvalidState());
        }
    }

    private VerificationResponse verify(HCertPayload hCertPayload) throws ResponseParseError {
        final String hCert;
        try {
            hCert = objectMapper.writeValueAsString(hCertPayload);

            final var uri =
                    UriComponentsBuilder.fromHttpUrl(baseurl + verifyEndpoint).build().toUri();
            final var request = RequestEntity.post(uri).headers(createRequestHeaders()).body(hCert);
            final var response = rt.exchange(request, String.class);

            if (!response.getStatusCode().equals(HttpStatus.OK)) {
                logger.info(
                        "Certificate couldn't be decoded: HTTP {}",
                        response.getStatusCode().value());
                return null;
            }

            return parseResponse(response);
        } catch (IOException e) {
            logger.error("Couldn't verify certificate", e);
            return null;
        }
    }

    private HttpHeaders createRequestHeaders() {
        var headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, "application/json");
        return headers;
    }

    private VerificationResponse parseResponse(ResponseEntity<String> response)
            throws ResponseParseError, JsonProcessingException {
        try {
            return objectMapper.readValue(response.getBody(), VerificationResponse.class);
        } catch (JsonMappingException ex) {
            throw new ResponseParseError(objectMapper.readTree(response.getBody()));
        } catch (Exception e) {
            throw new ResponseParseError(null);
        }
    }
}
