package ru.gdebenz.fuel.tbank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * Builds an SSLContext that trusts the default CAs plus the Russian Trusted Root CA bundled as a
 * classpath PEM — scoped to the T-Bank client only. Tolerant: if the PEM is missing or anything
 * fails, falls back to default trust (T-Bank TLS then fails and its column simply shows no data).
 */
public final class TbankTls {

    private static final Logger log = LoggerFactory.getLogger(TbankTls.class);

    private TbankTls() {
    }

    public static SSLContext buildContext(String caResource) {
        try {
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            int index = 0;

            // 1) default system-trusted CAs
            TrustManagerFactory defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            defaults.init((KeyStore) null);
            for (TrustManager tm : defaults.getTrustManagers()) {
                if (tm instanceof X509TrustManager x509) {
                    for (X509Certificate cert : x509.getAcceptedIssuers()) {
                        trust.setCertificateEntry("default-" + (index++), cert);
                    }
                }
            }

            // 2) the vendored Russian Trusted Root CA (may be absent)
            int added = 0;
            try (InputStream in = TbankTls.class.getResourceAsStream(caResource)) {
                if (in != null) {
                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                    for (Certificate cert : cf.generateCertificates(in)) {
                        trust.setCertificateEntry("russian-" + (index++), cert);
                        added++;
                    }
                }
            }
            if (added == 0) {
                log.warn("T-Bank CA resource {} not found/empty — T-Bank TLS will fail until it is added", caResource);
            } else {
                log.info("Loaded {} Russian Trusted CA certificate(s) for T-Bank from {}", added, caResource);
            }

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (Exception e) {
            log.warn("Failed to build T-Bank SSLContext; falling back to default trust", e);
            try {
                return SSLContext.getDefault();
            } catch (Exception ex) {
                throw new IllegalStateException("No SSLContext available", ex);
            }
        }
    }
}
