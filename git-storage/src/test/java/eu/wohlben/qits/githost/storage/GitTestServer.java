package eu.wohlben.qits.githost.storage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.zip.GZIPInputStream;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefAdvertiser.PacketLineOutRefAdvertiser;
import org.eclipse.jgit.transport.UploadPack;

/**
 * The three smart-HTTP endpoints, in this process, on a loopback port.
 *
 * <p>A transcription of {@code GitHostRoutes} — {@code info/refs}, {@code git-upload-pack}, {@code
 * git-receive-pack} — and transcribing it is the point: those routes take a {@code Repository}, so
 * serving a {@link QitsDfsRepository} through them is the proof that the real host needs no change
 * above the one method that opens the repository.
 *
 * <p>Every request <b>opens a new repository object</b> over the same two ports, exactly as the real
 * host opens a bare per request. So nothing in this suite can pass by accident on state cached in a
 * long-lived instance; if a push were not durable through the catalog, the very next request would
 * lose it.
 *
 * <p>No network is involved: the server binds loopback on a port the OS picks.
 */
final class GitTestServer implements AutoCloseable {

  private final HttpServer server;
  private final PackBlobStore blobs;
  private final PackCatalog catalog;

  private volatile PreReceiveHook preReceive = PreReceiveHook.NULL;

  private GitTestServer(HttpServer server, PackBlobStore blobs, PackCatalog catalog) {
    this.server = server;
    this.blobs = blobs;
    this.catalog = catalog;
  }

  static GitTestServer serving(PackBlobStore blobs, PackCatalog catalog) throws IOException {
    HttpServer http =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    GitTestServer server = new GitTestServer(http, blobs, catalog);
    http.createContext("/", server::handle);
    http.start();
    return server;
  }

  String url(String repositoryId) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + repositoryId;
  }

  /** Opens the repository the same way the server does, for a test to read or write directly. */
  QitsDfsRepository open(String repositoryId) {
    return new QitsDfsRepositoryBuilder()
        .setRepositoryId(repositoryId)
        .setPackBlobStore(blobs)
        .setPackCatalog(catalog)
        .build();
  }

  /**
   * Installs a hook that refuses one ref, the way {@code ProtectedRefHook} refuses the default
   * branch. The refusal message is what the pushing client prints.
   */
  void refusing(String refName, String message) {
    preReceive =
        (receivePack, commands) ->
            commands.stream()
                .filter(command -> command.getRefName().equals(refName))
                .forEach(
                    command ->
                        command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, message));
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    try (QitsDfsRepository repository = open(repositoryId(path))) {
      if (path.endsWith("/info/refs")) {
        advertise(exchange, repository, parameter(exchange.getRequestURI().getQuery(), "service"));
      } else if (path.endsWith("/git-upload-pack") || path.endsWith("/git-receive-pack")) {
        service(exchange, repository, path.substring(path.lastIndexOf('/') + 1));
      } else {
        exchange.sendResponseHeaders(404, -1);
      }
    } catch (RuntimeException e) {
      // A 500 with no body is what a broken server looks like to git; the stack trace is for
      // whoever is reading the test output.
      e.printStackTrace();
      exchange.sendResponseHeaders(500, -1);
    } finally {
      exchange.close();
    }
  }

  private void advertise(HttpExchange exchange, QitsDfsRepository repository, String service)
      throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    PacketLineOut packets = new PacketLineOut(buffer);
    packets.writeString("# service=" + service + "\n");
    packets.end();
    PacketLineOutRefAdvertiser advertiser = new PacketLineOutRefAdvertiser(packets);
    if ("git-upload-pack".equals(service)) {
      UploadPack upload = new UploadPack(repository);
      upload.setBiDirectionalPipe(false);
      upload.sendAdvertisedRefs(advertiser);
    } else {
      ReceivePack receive = receivePack(repository);
      receive.sendAdvertisedRefs(advertiser);
    }
    send(exchange, "application/x-" + service + "-advertisement", buffer.toByteArray());
  }

  private void service(HttpExchange exchange, QitsDfsRepository repository, String service)
      throws IOException {
    InputStream in = exchange.getRequestBody();
    if ("gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"))) {
      in = new GZIPInputStream(in);
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if ("git-upload-pack".equals(service)) {
      UploadPack upload = new UploadPack(repository);
      upload.setBiDirectionalPipe(false);
      upload.upload(in, out, null);
    } else {
      receivePack(repository).receive(in, out, null);
    }
    send(exchange, "application/x-" + service + "-result", out.toByteArray());
  }

  /**
   * Push options are enabled on <b>both</b> ReceivePack instances — this one and the advertising one
   * — because a client only sends {@code -o} if the capability was offered, and setting it on one of
   * the two produces the confusing failure where every option is silently never seen.
   */
  private ReceivePack receivePack(QitsDfsRepository repository) {
    ReceivePack receive = new ReceivePack(repository);
    receive.setBiDirectionalPipe(false);
    receive.setAllowPushOptions(true);
    receive.setPreReceiveHook(preReceive);
    return receive;
  }

  private static void send(HttpExchange exchange, String contentType, byte[] body)
      throws IOException {
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
  }

  private static String repositoryId(String path) {
    String withoutLeadingSlash = path.startsWith("/") ? path.substring(1) : path;
    int slash = withoutLeadingSlash.indexOf('/');
    return slash < 0 ? withoutLeadingSlash : withoutLeadingSlash.substring(0, slash);
  }

  private static String parameter(String query, String key) {
    if (query == null) {
      return null;
    }
    for (String pair : query.split("&")) {
      int equals = pair.indexOf('=');
      if (equals > 0 && pair.substring(0, equals).equals(key)) {
        return pair.substring(equals + 1);
      }
    }
    return null;
  }
}
