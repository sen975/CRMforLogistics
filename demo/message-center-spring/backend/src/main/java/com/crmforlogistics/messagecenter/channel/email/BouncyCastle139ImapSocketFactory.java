package com.crmforlogistics.messagecenter.channel.email;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;

final class BouncyCastle139ImapSocketFactory extends SSLSocketFactory {
    static final String CIPHER_SUITE = "TLS_RSA_WITH_AES_256_GCM_SHA384";
    private static final String PROTOCOL = "TLSv1.2";
    private static final String BC_PROVIDER = "BC";
    private static final String BCJSSE_PROVIDER = "BCJSSE";

    private final SSLSocketFactory delegate;
    private final String serverName;

    private BouncyCastle139ImapSocketFactory(SSLSocketFactory delegate, String serverName) {
        this.delegate = delegate;
        this.serverName = serverName;
    }

    static BouncyCastle139ImapSocketFactory create(String serverName) {
        try {
            installProviders();
            SSLContext context = SSLContext.getInstance("TLS", BCJSSE_PROVIDER);
            context.init(null, trustManagers(), null);
            return new BouncyCastle139ImapSocketFactory(context.getSocketFactory(), serverName);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to initialize 139 IMAP TLS compatibility", ex);
        }
    }

    @Override public String[] getDefaultCipherSuites() { return new String[]{CIPHER_SUITE}; }
    @Override public String[] getSupportedCipherSuites() { return new String[]{CIPHER_SUITE}; }

    @Override public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
        return configure(delegate.createSocket(socket, host, port, autoClose));
    }
    @Override public Socket createSocket(String host, int port) throws IOException {
        return configure(delegate.createSocket(host, port));
    }
    @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return configure(delegate.createSocket(host, port, localHost, localPort));
    }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException {
        return configure(delegate.createSocket(host, port));
    }
    @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return configure(delegate.createSocket(address, port, localAddress, localPort));
    }

    private Socket configure(Socket socket) {
        if (socket instanceof SSLSocket sslSocket) {
            SSLParameters parameters = sslSocket.getSSLParameters();
            parameters.setProtocols(new String[]{PROTOCOL});
            parameters.setCipherSuites(new String[]{CIPHER_SUITE});
            if (serverName != null && !serverName.isBlank()) {
                parameters.setServerNames(List.of(new SNIHostName(serverName)));
            }
            sslSocket.setSSLParameters(parameters);
        }
        return socket;
    }

    private static synchronized void installProviders() {
        if (Security.getProvider(BC_PROVIDER) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        if (Security.getProvider(BCJSSE_PROVIDER) == null) {
            Security.addProvider(new BouncyCastleJsseProvider());
        }
    }

    private static TrustManager[] trustManagers() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                return new TrustManager[]{new KeRsaTrustManager(x509)};
            }
        }
        return factory.getTrustManagers();
    }

    private static final class KeRsaTrustManager implements X509TrustManager {
        private final X509TrustManager delegate;

        private KeRsaTrustManager(X509TrustManager delegate) { this.delegate = delegate; }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, "KE:RSA".equalsIgnoreCase(authType) ? "RSA" : authType);
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, "KE:RSA".equalsIgnoreCase(authType) ? "RSA" : authType);
        }
        @Override public X509Certificate[] getAcceptedIssuers() { return delegate.getAcceptedIssuers(); }
    }
}
