# Phase 2.1: HTTP and the client-server foundation

**Student:** Rifat Al Maruf (BSSE1705), IIT, University of Dhaka

**Record date:** October 26, 2026

## 1. Objective and architecture

Task 2.1 adds a minimal HTTP server with a health endpoint. The supervisor has
made client-server architecture mandatory. The intended system has a browser
client using HTML, CSS, and JavaScript, communicating over HTTP with a Java
server. The server will call the manually implemented Information Retrieval
core. That integration and browser interface are still planned.

A client initiates a request; a server listens and returns a response. For the
health check, a command-line HTTP tool can act as the client. Receiving `OK`
demonstrates that the endpoint responds, not that document search is available.

## 2. HTTP, addresses, and endpoints

HTTP defines requests and responses. A request identifies a method and target;
a response contains a status code, headers, and sometimes a body. The current
plain HTTP connection uses TCP, which provides an ordered byte stream.

For `http://127.0.0.1:8080/health`:

| Part | Meaning |
| --- | --- |
| `http` | The application protocol; this endpoint does not use TLS |
| `127.0.0.1` | IPv4 loopback: the computer making the connection |
| `8080` | TCP port on which this server listens |
| `/health` | Endpoint path checked by the handler |

`localhost` is a hostname normally resolving to loopback addresses. This server
binds explicitly to IPv4 `127.0.0.1`; using that address avoids ambiguity with
IPv6 localhost resolution. On another computer, `127.0.0.1` refers to that other
computer, not this server. A cross-computer demonstration requires later network
configuration and verification. It has not been completed.

## 3. Request-response lifecycle

1. The client connects to `127.0.0.1:8080` and sends a request.
2. `HttpServer` selects a registered context and invokes its request handler.
3. The handler checks the exact path, then the method.
4. It sends a status and headers, and writes a body for a successful health GET.
5. It closes the response stream and exchange resources.

Closing an exchange completes that request's processing. It does not stop the
listening server or necessarily mean that every underlying TCP connection is
immediately discarded.

## 4. Java classes used

[SearchServer.java](../src/SearchServer.java) has a separate `main()` method from
the original [CLI](../src/Main.java). It uses only JDK classes.

| API | Responsibility in this implementation |
| --- | --- |
| `HttpServer` | Built-in lightweight HTTP server from module `jdk.httpserver` |
| `InetSocketAddress` | Combines the address `127.0.0.1` and port `8080` |
| `HttpHandler` | Request-handling interface, supplied here as a lambda |
| `HttpExchange` | Provides the current request, response headers, and response stream |
| `OutputStream` | Writes response bytes |
| `StandardCharsets.UTF_8` | Selects an explicit character encoding |

The built-in server is sufficient for this small educational endpoint and avoids
introducing a framework or external dependency. It is infrastructure; it does
not implement the project's search algorithms.

## 5. Implementation walkthrough

The first operation creates and binds the server:

```java
HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
```

The backlog argument `0` leaves the pending-connection queue setting to the
system default. If binding fails, `IOException` propagates from `main`; no
successful startup message is printed.

`createContext("/", exchange -> { ... })` installs one handler for paths under
the root context. Context routing uses path prefixes, so the handler performs
its own exact check:

```java
if (!"/health".equals(exchange.getRequestURI().getRawPath())) {
    exchange.sendResponseHeaders(404, -1);
}
```

The raw path must equal `/health`, including case. Thus `/health/`,
`/healthcheck`, and `/health/extra` are rejected. Percent-encoded alternatives
are not decoded into accepted aliases. A query string is separate from the
path; the current handler does not inspect query parameters.

For the exact path, the handler checks `GET`. Other methods receive 405 and
`Allow: GET`. Unknown paths receive 404 before method validation.

| Request | Response |
| --- | --- |
| `GET /health` | 200 OK; body exactly `OK` |
| `POST /health` | 405 Method Not Allowed; `Allow: GET` |
| Other methods on `/health` | 405; `Allow: GET` |
| Other paths | 404 Not Found |

The error responses use length `-1`, indicating no response body. For success,
the code converts `OK` to UTF-8 bytes, sets
`Content-Type: text/plain; charset=utf-8`, and sends the byte length, which is
2. There is no newline in the body. A header describes the encoding; converting
the string with UTF-8 is what actually produces the encoded bytes.

```java
try (OutputStream output = exchange.getResponseBody()) {
    output.write(response);
}
```

Try-with-resources closes the stream even if writing throws an exception.
The surrounding `finally` calls `exchange.close()` for every routing branch,
including errors. Finally, `server.start()` begins serving, and the program
prints `Listening on http://127.0.0.1:8080`. The application keeps running until
it is stopped.

## 6. Verification record and its limits

These are recorded results from the completed Task 2.1 verification, not fresh
HTTP tests performed while writing this document:

| Check | Previously verified result |
| --- | --- |
| Compilation using explicit Temurin JDK 25.0.3 | PASS |
| Existing seven baseline regression tests | 7 passed, 0 failed |
| `GET /health` | 200; exact two-byte UTF-8 body `OK` |
| Successful response Content-Type | `text/plain; charset=utf-8` |
| `GET /unknown` | 404 |
| `GET /health/`, `/healthcheck`, `/health/extra` | 404 |
| `POST /health` | 405 with `Allow: GET` |
| Owned verification server | Stopped after checks |

The seven baseline tests exercise the IR core, not HTTP behavior. During the
subsequent documentation workflow, compilation and all seven tests passed
again, but a repeat HTTP launch failed with
`java.net.BindException: Address already in use`. Port 8080 was occupied. That
attempt did not reverify the endpoints and did not invalidate the earlier
successful checks. No Java source changed between those checks and this record.
No unknown process was terminated, and no fresh HTTP run was required for this
documentation milestone.

An earlier PowerShell `Start-Process` attempt also encountered duplicate
`Path`/`PATH` environment entries. The original successful verification used
`System.Diagnostics.Process` to launch its own server without changing persistent
environment settings. A later combined launch-and-cleanup script was blocked by
automatic approval review. These are distinct verification-tool issues, not
evidence of an HTTP handler defect.

See the [project README](../README.md) for compilation and launch commands.
Start the server only when port 8080 is available; a bind conflict is not a reason
to terminate an unidentified process.

## 7. Limitations and planned integration

- The server listens only on loopback with fixed port 8080.
- There is no search API, JSON response format, browser interface, or static
  website hosting yet.
- `SearchServer` does not create or call `SearchEngine`; health success says
  nothing about index readiness.
- Cross-computer operation, load handling, and performance are unverified.
- There is no TLS, authentication, configurable port, or application shutdown hook.

The next planned integration will connect HTTP query handling to
[SearchEngine](../src/SearchEngine.java), validate request input, and return
structured results. The browser client will follow. This is a plan, not work
implemented in Task 2.1, and Task 2.2 has not started.

## 8. Five viva questions

1. **What is the client here?** The program sending the HTTP request, such as a
   command-line tool now or a browser interface later.
2. **Why use port 8080?** It is the chosen listening port. A port distinguishes
   a network service on an address, and it must be available for binding.
3. **Why check the exact path after `createContext`?** Context matching uses
   prefixes; the explicit comparison prevents accepting unwanted path suffixes.
4. **How do 404 and 405 differ?** 404 means the path is unavailable; 405 means
   the path exists but the method is unsupported. `Allow` lists supported methods.
5. **Does `OK` prove that search works over HTTP?** No. It only verifies the
   health endpoint; the search API is still planned.

## 9. Personal reflection — complete personally

- Explain one complete health request in your own words.
- Why would `127.0.0.1` fail to reach this server from a second computer?
- Explain why the body length is measured in bytes rather than characters.
- Describe the distinction between the successful test and the occupied-port retry.
- Which behavior must remain unchanged when SearchEngine integration begins?

Return to the [learning index](README.md).
