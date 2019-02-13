package biz.karms.tomcat.embedded.nativetlsdemo;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.AprLifecycleListener;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.coyote.http2.Http2Protocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.StringTokenizer;


/**
 * @author Michal Karm Babacek <karm@redhat.com>
 */
public class Server {

    // Env props
    private static final int TC_PORT = Integer.parseInt(System.getProperty("TC_PORT", "8443"));
    private static final int TC_MAX_THREADS = Integer.parseInt(System.getProperty("TC_MAX_THREADS", "150"));
    private static final int TC_CONNECTION_TIMEOUT_MS = Integer.parseInt(System.getProperty("TC_CONNECTION_TIMEOUT_MS", "20000"));
    private static final String TC_CA_CERT_PEM_BASE64 = System.getProperty("TC_CA_CERT_PEM_BASE64");
    private static final String TC_SERVER_CERT_PEM_BASE64 = System.getProperty("TC_SERVER_CERT_PEM_BASE64");
    private static final String TC_SERVER_KEY_PEM_BASE64 = System.getProperty("TC_SERVER_KEY_PEM_BASE64");
    private static final String BASE_DIR = new File(".").getAbsolutePath();
    private static boolean init = Boolean.FALSE;

    public static void main(String[] args) throws LifecycleException, IOException, ClassNotFoundException {
        loadTomcatNativeLibrary();

        prepareCertFilesFromBase64();

        // Tomcat server
        final Tomcat tomcat = new Tomcat();
        final Service service = tomcat.getService();
        service.addConnector(Server.getSslConnector());
        service.addLifecycleListener(getAprListener());
        tomcat.setPort(TC_PORT);
        tomcat.setBaseDir(BASE_DIR);
        final Context ctx = tomcat.addContext("", new File(".").getAbsolutePath());
        Tomcat.addServlet(ctx, "fire", new HttpServlet() {
            private static final long serialVersionUID = 9834729384722698L;

            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.setCharacterEncoding("UTF-8");
                response.setContentType("text/plain");
                try (Writer writer = response.getWriter()) {
                    writer.write("\uD83D\uDD25\uD83D\uDD25\uD83D\uDD25");
                    writer.flush();
                }
            }
        });
        ctx.addServletMappingDecoded("/*", "fire");
        tomcat.start();
        tomcat.getServer().await();
    }

    private static void prepareCertFilesFromBase64() throws IOException {
        if (TC_CA_CERT_PEM_BASE64 == null || TC_SERVER_CERT_PEM_BASE64 == null || TC_SERVER_KEY_PEM_BASE64 == null) {
            System.err.println("Check env properties: TC_CA_CERT_PEM_BASE64, TC_SERVER_CERT_PEM_BASE64, TC_SERVER_KEY_PEM_BASE64");
            System.exit(1);
        }
        Files.write(Paths.get(BASE_DIR + "/ca.cert.pem"), Base64.getDecoder().decode(TC_CA_CERT_PEM_BASE64));
        Files.write(Paths.get(BASE_DIR + "/server.cert.pem"), Base64.getDecoder().decode(TC_SERVER_CERT_PEM_BASE64));
        Files.write(Paths.get(BASE_DIR + "/server.key.nopass.pem"), Base64.getDecoder().decode(TC_SERVER_KEY_PEM_BASE64));
    }

    private static Connector getSslConnector() {
        Connector con = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        con.setPort(TC_PORT);

        Http11NioProtocol protocol = (Http11NioProtocol) con.getProtocolHandler();
        protocol.setSSLEnabled(true);
        protocol.setMaxThreads(TC_MAX_THREADS);
        protocol.setSslImplementationName("org.apache.tomcat.util.net.openssl.OpenSSLImplementation");
        protocol.setConnectionTimeout(TC_CONNECTION_TIMEOUT_MS);

        Http2Protocol http2 = new Http2Protocol();
        con.addUpgradeProtocol(http2);

        SSLHostConfig sslConf = new SSLHostConfig();
        sslConf.setConfigType(SSLHostConfig.Type.OPENSSL);
        sslConf.setCaCertificateFile(BASE_DIR + "/ca.cert.pem");

        SSLHostConfigCertificate cert = new SSLHostConfigCertificate(sslConf, SSLHostConfigCertificate.Type.RSA);
        cert.setCertificateFile(BASE_DIR + "/server.cert.pem");
        cert.setCertificateKeyFile(BASE_DIR + "/server.key.nopass.pem");
        sslConf.addCertificate(cert);

        con.addSslHostConfig(sslConf);

        return con;
    }

    private static LifecycleListener getAprListener() {
        final AprLifecycleListener apr = new AprLifecycleListener();
        apr.setUseOpenSSL(true);
        return apr;
    }

    // FIXME: Should move this to a separated project and create the maven dependency
    private static void loadTomcatNativeLibrary() throws IOException, ClassNotFoundException {
        // FIXME: Only working for linux
        if (!init) {
            ClassLoader classLoader = Server.class.getClassLoader();
            String[] files = {"libapr-1.so.0.6.2", "libtcnative-1.so.0.2.21"};
            /*File temp = File.createTempFile("tmp-", "openssl");
            temp.delete();
            temp.mkdir();*/
            for (String file : files) {
                final InputStream resource = classLoader.getResourceAsStream(file);
                if (resource != null) {
                    File result = new File("/opt/tomcat", file);
                    result.getParentFile().mkdirs();
                    try (FileOutputStream out = new FileOutputStream(result)) {
                        byte[] buf = new byte[1000];
                        int r;
                        while ((r = resource.read(buf)) > 0) {
                            out.write(buf, 0, r);
                        }
                    }
                    result.deleteOnExit();
                    System.out.println("Will load: " + result);
                    System.load(result.getAbsolutePath());
                    addLibraryPath(result.getAbsolutePath());
                    //lib64
                    System.out.println("Loaded: " + result + " Done!");
                }
            }
            addLibraryPath("/opt/tomcat/");
            try{
                System.loadLibrary("libtcnative");
            }catch (Error e) {
                System.out.println("Erro ao tentar libtcnative");
            }
            try{
                System.loadLibrary("libtcnative1");
            }catch (Error e) {
                System.out.println("Erro ao tentar libtcnative1");
            }
            try{
                System.loadLibrary("libtcnative-1");
            }catch (Error e) {
                System.out.println("Erro ao tentar libtcnative-1");
            }
            init = Boolean.TRUE;
            //temp.deleteOnExit();
        }
    }

    public static void addLibraryPath(String pathToAdd){
        try {
            final Field usrPathsField = ClassLoader.class.getDeclaredField("usr_paths");

            usrPathsField.setAccessible(true);

            //get array of paths
            final String[] paths = (String[]) usrPathsField.get(null);

            //check if the path to add is already present
            for (String path : paths) {
                if (path.equals(pathToAdd)) {
                    return;
                }
            }

            //add the new path
            final String[] newPaths = Arrays.copyOf(paths, paths.length + 1);
            newPaths[newPaths.length - 1] = pathToAdd;
            usrPathsField.set(null, newPaths);
        } catch (Exception e) {
            System.err.println("Error adding to lib path");
        }
    }
}
