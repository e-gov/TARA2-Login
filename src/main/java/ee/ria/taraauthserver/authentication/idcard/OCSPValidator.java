package ee.ria.taraauthserver.authentication.idcard;

import ee.ria.taraauthserver.error.exceptions.OCSPServiceNotAvailableException;
import ee.ria.taraauthserver.error.exceptions.OCSPValidationException;
import ee.ria.taraauthserver.logging.ClientRequestLogger;
import ee.ria.taraauthserver.utils.X509Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.Conversion;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.OCSPException;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPReqBuilder;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.SingleResp;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentVerifierProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Security;
import java.security.SignatureException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static ee.ria.taraauthserver.config.properties.AuthConfigurationProperties.Ocsp;
import static ee.ria.taraauthserver.logging.ClientRequestLogger.Service;
import static java.util.Map.of;
import static net.logstash.logback.argument.StructuredArguments.value;
import static net.logstash.logback.marker.Markers.append;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "tara.auth-methods.id-card.enabled")
public class OCSPValidator {
    private final Map<String, X509Certificate> trustedCertificates;
    private final ClientRequestLogger requestLogger = new ClientRequestLogger(Service.OCSP, this.getClass());

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Autowired
    private SSLContext sslContext;

    @Autowired
    private final OCSPConfigurationResolver ocspConfigurationResolver;

    public Ocsp checkCert(X509Certificate userCert) {
        Assert.notNull(userCert, "User certificate cannot be null!");
        log.info("OCSP certificate validation. Serialnumber=<{}>, SubjectDN=<{}>, issuerDN=<{}>",
                value("x509.serial_number", userCert.getSerialNumber().toString()),
                value("x509.subject.distinguished_name", userCert.getSubjectDN().getName()),
                value("x509.issuer.distinguished_name", userCert.getIssuerDN().getName()));
        List<Ocsp> ocspConfiguration = ocspConfigurationResolver.resolve(userCert);
        Assert.isTrue(!CollectionUtils.isEmpty(ocspConfiguration), "At least one OCSP configuration must be present");

        int count = 0;
        int maxTries = ocspConfiguration.size();

        while (true) {
            Ocsp ocspConf = ocspConfiguration.get(count);
            try {
                if (count > 0) {
                    log.info(append("ocsp.conf", ocspConf), "Retrying OCSP request to: {}", value("url.full", ocspConf.getUrl()));
                }
                checkCert(userCert, ocspConf);
                return ocspConf;
            } catch (OCSPServiceNotAvailableException e) {
                if (++count == maxTries) throw e;
            }
        }
    }

    protected void checkCert(X509Certificate userCert, Ocsp ocspConf) {
        X509Certificate issuerCert = findIssuerCertificate(userCert);
        validateCertSignedBy(userCert, issuerCert);

        try {
            OCSPReq request = buildOCSPReq(userCert, issuerCert, ocspConf);
            OCSPResp response = sendOCSPReq(request, ocspConf);

            validateOCSPResponse(response);

            BasicOCSPResp ocspResponse = getResponse(response);
            validateResponseNonce(request, ocspResponse, ocspConf);
            validateResponseSignature(ocspResponse, issuerCert, ocspConf);

            SingleResp singleResponse = getSingleResp(ocspResponse, request.getRequestList()[0].getCertID());
            validateResponseThisUpdate(singleResponse, ocspConf.getAcceptedClockSkewInSeconds(), ocspConf.getResponseLifetimeInSeconds());
            validateCertStatus(singleResponse);
        } catch (OCSPValidationException | OCSPServiceNotAvailableException e) {
            throw e;
        } catch (SocketTimeoutException | SocketException | UnknownHostException | SSLException e) {
            throw new OCSPServiceNotAvailableException("OCSP not available: " + ocspConf.getUrl(), e);
        } catch (Exception e) {
            throw new IllegalStateException("OCSP validation failed: " + e.getMessage(), e);
        }
    }

    private void validateOCSPResponse(OCSPResp ocspResp) throws OCSPException {
        if (ocspResp.getResponseObject() == null) {
            throw new OCSPServiceNotAvailableException("Invalid OCSP response! Response returned empty body!");
        } else if (ocspResp.getStatus() < 0 || ocspResp.getStatus() > 6) {
            throw new OCSPServiceNotAvailableException("Invalid OCSP response! Response status is missing or invalid!");
        } else if (ocspResp.getStatus() == OCSPResp.INTERNAL_ERROR) {
            throw new OCSPServiceNotAvailableException("Response returned Internal Server error!");
        } else if (ocspResp.getStatus() == OCSPResp.TRY_LATER) {
            throw new OCSPServiceNotAvailableException("Response returned Try Later error!");
        }
    }

    private BasicOCSPResp getResponse(OCSPResp response) throws OCSPException {
        BasicOCSPResp basicOCSPResponse = (BasicOCSPResp) response.getResponseObject();
        Assert.notNull(basicOCSPResponse, "Invalid OCSP response! OCSP response object bytes could not be read!");
        Assert.notNull(basicOCSPResponse.getCerts(), "Invalid OCSP response! OCSP response is missing mandatory element - the signing certificate");
        Assert.isTrue(basicOCSPResponse.getCerts().length >= 1, "Invalid OCSP response! Expecting at least one OCSP responder certificate");
        return basicOCSPResponse;
    }

    private void validateCertStatus(SingleResp singleResponse) {
        org.bouncycastle.cert.ocsp.CertificateStatus status = singleResponse.getCertStatus();
        if (status != org.bouncycastle.cert.ocsp.CertificateStatus.GOOD) {
            if (status instanceof RevokedStatus) {
                throw OCSPValidationException.of(CertificateStatus.REVOKED);
            } else {
                throw OCSPValidationException.of(CertificateStatus.UNKNOWN);
            }
        }
    }

    private OCSPReq buildOCSPReq(X509Certificate userCert, X509Certificate issuerCert, Ocsp conf)
            throws OCSPException, IOException, CertificateEncodingException, OperatorCreationException {
        OCSPReqBuilder builder = new OCSPReqBuilder();

        CertificateID certificateID = generateCertificateIdForRequest(userCert, issuerCert);
        builder.addRequest(certificateID);

        if (!conf.isNonceDisabled()) {
            DEROctetString nonce = generateDerOctetStringForNonce(UUID.randomUUID());
            Extension extension = new Extension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce, true, nonce);
            builder.setRequestExtensions(new Extensions(new Extension[]{extension}));
        }

        return builder.build();
    }

    private OCSPResp sendOCSPReq(OCSPReq request, Ocsp conf) throws IOException {
        byte[] bytes = request.getEncoded();
        requestLogger.logRequest(conf.getUrl(), HttpMethod.GET, of("http.request.body.content",
                Base64.getEncoder().encodeToString(bytes), "ocsp.conf", conf));
        HttpURLConnection connection = (HttpURLConnection) getHttpURLConnection(new URL(conf.getUrl()));
        connection.setRequestProperty("Content-Type", "application/ocsp-request");
        connection.setRequestProperty("Accept", "application/ocsp-response");
        connection.setConnectTimeout(conf.getConnectTimeoutInMilliseconds());
        connection.setReadTimeout(conf.getReadTimeoutInMilliseconds());
        connection.setDoOutput(true);
        try (DataOutputStream outputStream = new DataOutputStream(new BufferedOutputStream(connection.getOutputStream()))) {
            outputStream.write(bytes);
            outputStream.flush();
        }

        if (connection.getResponseCode() == 200) {
            String contentType = connection.getHeaderField("Content-Type");
            if (StringUtils.isEmpty(contentType) || !contentType.equals("application/ocsp-response")) {
                throw new OCSPServiceNotAvailableException("Response Content-Type header is missing or invalid. " +
                        "Expected: 'application/ocsp-response', actual: " + contentType);
            }

            try (InputStream in = (InputStream) connection.getContent()) {
                OCSPResp ocspResp = new OCSPResp(in);
                requestLogger.logResponse(connection.getResponseCode(), Base64.getEncoder().encodeToString(ocspResp.getEncoded()));
                return ocspResp;
            }
        } else {
            try (InputStream in = connection.getErrorStream()) {
                if (in != null && in.available() != 0) {
                    String response = IOUtils.toString(in, StandardCharsets.UTF_8);
                    requestLogger.logResponse(connection.getResponseCode(), response);
                } else {
                    requestLogger.logResponse(connection.getResponseCode());
                }
            }
            throw new OCSPServiceNotAvailableException(String.format("Service returned HTTP status code %d",
                    connection.getResponseCode()));
        }
    }

    private URLConnection getHttpURLConnection(URL obj) throws IOException {
        if (obj.getProtocol().equals("https"))
            return getHttpsURLConnection(obj);
        else
            return obj.openConnection();
    }

    private HttpsURLConnection getHttpsURLConnection(URL obj) throws IOException {
        HttpsURLConnection httpsURLConnection = (HttpsURLConnection) obj.openConnection();
        httpsURLConnection.setSSLSocketFactory(sslContext.getSocketFactory());
        return httpsURLConnection;
    }

    private SingleResp getSingleResp(BasicOCSPResp basicOCSPResponse, CertificateID certificateID) {
        Optional<SingleResp> singleResponse = Arrays.stream(basicOCSPResponse.getResponses())
                .filter(singleResp -> singleResp.getCertID().equals(certificateID))
                .findFirst();
        Assert.isTrue(singleResponse.isPresent(), "No OCSP response is present");
        return singleResponse.get();
    }

    private CertificateID generateCertificateIdForRequest(X509Certificate userCert, X509Certificate issuerCert)
            throws OperatorCreationException, CertificateEncodingException, OCSPException {
        BigInteger userCertSerialNumber = userCert.getSerialNumber();
        return new CertificateID(
                new JcaDigestCalculatorProviderBuilder().build().get(CertificateID.HASH_SHA1), // NB! SK OCSP supports only SHA-1 for CertificateID
                new JcaX509CertificateHolder(issuerCert),
                userCertSerialNumber
        );
    }

    private DEROctetString generateDerOctetStringForNonce(UUID uuid) throws IOException {
        byte[] uuidBytes = Conversion.uuidToByteArray(uuid, new byte[16], 0, 16);
        return new DEROctetString(new DEROctetString(uuidBytes));
    }

    private void validateResponseNonce(OCSPReq request, BasicOCSPResp response, Ocsp ocspConf) {
        if (!ocspConf.isNonceDisabled()) {
            Extension requestExtension = request.getExtension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce);
            DEROctetString nonce = (DEROctetString) requestExtension.getExtnValue();

            Extension responseExtension = response.getExtension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce);
            if (responseExtension == null)
                throw new IllegalStateException("No nonce found in OCSP response");

            DEROctetString receivedNonce = (DEROctetString) responseExtension.getExtnValue();
            if (!nonce.equals(receivedNonce))
                throw new IllegalStateException("Invalid OCSP response nonce");
        }
    }

    private void validateResponseThisUpdate(SingleResp response, long acceptedClockSkew, long responseLifetime) {
        final Instant thisUpdate = response.getThisUpdate().toInstant();
        final Instant now = Instant.now();

        if (thisUpdate.isBefore(now.minusSeconds(acceptedClockSkew + responseLifetime)))
            throw new IllegalStateException("OCSP response was older than accepted");
        if (thisUpdate.isAfter(now.plusSeconds(acceptedClockSkew)))
            throw new IllegalStateException("OCSP response cannot be produced in the future");
    }

    private void validateResponseSignature(BasicOCSPResp response, X509Certificate userCertIssuer, Ocsp ocspConfiguration)
            throws OCSPException, OperatorCreationException, CertificateException, IOException {

        X509Certificate signingCert = getResponseSigningCert(response, userCertIssuer, ocspConfiguration);
        Assert.isTrue(signingCert.getExtendedKeyUsage() != null
                        && signingCert.getExtendedKeyUsage().contains(KeyPurposeId.id_kp_OCSPSigning.getId()),
                "This certificate has no OCSP signing extension (subjectDn='" + signingCert.getSubjectDN() + "')");
        verifyResponseSignature(response, signingCert);
    }

    private X509Certificate getResponseSigningCert(BasicOCSPResp response, X509Certificate userCertIssuer, Ocsp ocspConfiguration)
            throws CertificateException, IOException {
        String responderCn = getResponderCN(response);
        // if explicit responder cert is set in configuration, then response signature MUST be verified with it
        if (ocspConfiguration.getResponderCertificateCn() != null) {
            X509Certificate signCert = trustedCertificates.get(ocspConfiguration.getResponderCertificateCn());

            Assert.notNull(signCert, "Certificate with CN: '" + ocspConfiguration.getResponderCertificateCn()
                    + "' is not trusted! Please check your configuration!");
            Assert.isTrue(responderCn.equals(ocspConfiguration.getResponderCertificateCn()),
                    "OCSP provider has signed the response using cert with CN: '" + responderCn
                            + "', but configuration expects response to be signed with a different certificate (CN: '"
                            + ocspConfiguration.getResponderCertificateCn() + "')!");
            return signCert;
        } else {
            // othwerwise the response must be signed with one of the trusted ocsp responder certs OR it's signer cert must be issued by the same CA as user cert
            X509Certificate signCert = trustedCertificates.get(responderCn);
            if (signCert == null) {
                signCert = getCertFromOcspResponse(response, responderCn);

                X509Certificate responderCertIssuerCert = findIssuerCertificate(signCert);
                if (responderCertIssuerCert.equals(userCertIssuer)) {
                    return signCert;
                } else {
                    throw new IllegalStateException("In case of AIA OCSP, the OCSP responder certificate must be issued " +
                            "by the authority that issued the user certificate. Expected issuer: '" + userCertIssuer.getSubjectX500Principal() + "', " +
                            "but the OCSP responder signing certificate was issued by '" + responderCertIssuerCert.getSubjectX500Principal() + "'");
                }
            } else {
                return signCert;
            }
        }
    }

    private X509Certificate getCertFromOcspResponse(BasicOCSPResp response, String cn) throws CertificateException, IOException {
        Optional<X509CertificateHolder> cert = Arrays.stream(response.getCerts()).filter(c -> X509Utils.getFirstCNFromX500Name(c.getSubject()).equals(cn)).findFirst();
        Assert.isTrue(cert.isPresent(), "Invalid OCSP response! Responder ID in response contains value: " + cn
                + ", but there was no cert provided with this CN in the response.");
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                new ByteArrayInputStream(cert.get().getEncoded())
        );
    }

    private void verifyResponseSignature(BasicOCSPResp response, X509Certificate responseSignCertificate) throws CertificateExpiredException, CertificateNotYetValidException, OperatorCreationException, OCSPException {
        responseSignCertificate.checkValidity();

        ContentVerifierProvider verifierProvider = new JcaContentVerifierProviderBuilder()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(responseSignCertificate.getPublicKey());

        response.isSignatureValid(verifierProvider);

        if (!response.isSignatureValid(verifierProvider))
            throw new IllegalStateException("OCSP response signature is not valid");
    }

    private X509Certificate findIssuerCertificate(X509Certificate certificate) {
        String issuerCN = X509Utils.getIssuerCNFromCertificate(certificate);
        log.debug("IssuerCN extracted: {}", value("x509.issuer.common_name", issuerCN));
        X509Certificate issuerCert = trustedCertificates.get(issuerCN);
        Assert.notNull(issuerCert, "Issuer certificate with CN '" + issuerCN + "' is not a trusted certificate!");
        return issuerCert;
    }

    private String getResponderCN(BasicOCSPResp response) {
        try {
            return X509Utils.getFirstCNFromX500Name(
                    response.getResponderId().toASN1Primitive().getName()
            );
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find responder CN from OCSP response", e);
        }
    }

    private void validateCertSignedBy(X509Certificate cert, X509Certificate signedBy) {
        try {
            cert.verify(signedBy.getPublicKey(), BouncyCastleProvider.PROVIDER_NAME);
        } catch (CertificateException | InvalidKeyException | NoSuchAlgorithmException | NoSuchProviderException |
                 SignatureException e) {
            throw new IllegalStateException("Failed to verify user certificate", e);
        }
    }
}
