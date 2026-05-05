package com.redis.assignment.exercise2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class RedisEnterpriseAdmin {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String API_BASE_URL =
            getenv("REDIS_API_BASE_URL", "https://re-cluster1.ps-redislabs.org:9443");
    private static final String API_USER =
            getenv("REDIS_API_USER", "admin@rl.org");
    private static final String API_PASSWORD =
            getenv("REDIS_API_PASSWORD", "qST2pbF");
    private static final String DB_NAME =
            getenv("REDIS_DB_NAME", "new-db");
    private static final String TEMP_USER_PASSWORD =
            getenv("REDIS_TEMP_USER_PASSWORD", "ChangeMe123!");

    private static final long MEMORY_SIZE_BYTES = 1_073_741_824L; // 1 GiB
    private static final int SHARDS_COUNT = 1;

    private final HttpClient client;
    private final String basicAuthHeader;

    
    public RedisEnterpriseAdmin() throws Exception {
        this.client = buildUnsafeClient();
    
        String token = API_USER + ":" + API_PASSWORD;
        this.basicAuthHeader = "Basic " + Base64.getEncoder()
                .encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpClient buildUnsafeClient() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) { }
    
                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) { }
    
                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new SecureRandom());
    
        return HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }
    

    public static void main(String[] args) throws Exception {
        new RedisEnterpriseAdmin().run();
    }

    private void run() throws Exception {
        long dbUid = createDatabase();
        System.out.println("Created database uid=" + dbUid + " name=" + DB_NAME);

        //as the cluster is RBAC-enabled, we'll use role_uids instead of role in the request body
        //and because initialized db only has role 'admin' with uid = 1
        //we need to create two roles required in this exercises (db_viewer and db_member)
        //curl -X POST -H "accept: application/json" -H "Content-Type: application/json" -u "admin@rl.org:qST2pbF" https://re-cluster1.ps-redislabs.org:9443/v1/roles -d '{"name":"db_viewer", "management": "db_viewer"}' -k -i
        //curl -X POST -H "accept: application/json" -H "Content-Type: application/json" -u "admin@rl.org:qST2pbF" https://re-cluster1.ps-redislabs.org:9443/v1/roles -d '{"name":"db_member", "management": "db_member"}' -k -i

        //lines for user creation below may fail if this java code has been executed before (hence same emails already registered for existing users)
        //you may delete these users first, easier using curl like below (assume uids are 2,3,4)
        //curl -X DELETE -H "accept: application/json" -u "admin@rl.org:qST2pbF" https://re-cluster1.ps-redislabs.org:9443/v1/users/2 -k -i
        //curl -X DELETE -H "accept: application/json" -u "admin@rl.org:qST2pbF" https://re-cluster1.ps-redislabs.org:9443/v1/users/3 -k -i
        //curl -X DELETE -H "accept: application/json" -u "admin@rl.org:qST2pbF" https://re-cluster1.ps-redislabs.org:9443/v1/users/4 -k -i
        try {
            createUser("John Doe", "john.doe@example.com", 4);
            createUser("Mike Smith", "mike.smith@example.com", 5);
            createUser("Cary Johnson", "cary.johnson@example.com", 1);
        }catch(Exception e){
            System.out.println("Exception on create user: " + e.getMessage());    
        }
        System.out.println("\nUsers:");
        listAndDisplayUsers();

        deleteDatabaseWithRetry(dbUid);
        System.out.println("\nDeleted database uid=" + dbUid);
    }

    private long createDatabase() throws Exception {
        ObjectNode bdb = MAPPER.createObjectNode();
        bdb.put("name", DB_NAME);
        bdb.put("type", "redis");
        bdb.put("memory_size", MEMORY_SIZE_BYTES);
        bdb.put("shards_count", SHARDS_COUNT);

        ObjectNode body = MAPPER.createObjectNode();
        body.set("bdb", bdb);

        HttpResponse<String> resp = sendJson("POST", "/v1/bdbs", bdb);
        ensureSuccess(resp, 200, 201);

        JsonNode json = parseJson(resp.body());

        // Create responses may return the BDB object directly or nested under "bdb".
        JsonNode dbNode = json.has("uid") ? json : json.path("bdb");
        long uid = dbNode.path("uid").asLong(-1);
        if (uid < 0) {
            throw new IllegalStateException("Create database response did not contain uid: " + resp.body());
        }
        return uid;
    }

    private void createUser(String name, String email, int roleId) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("email", email);
        body.put("password", TEMP_USER_PASSWORD);
        body.put("name", name);
        body.put("email_alerts", false);
        body.put("auth_method", "regular");

        ArrayNode roleUids = body.putArray("role_uids");
        roleUids.add(roleId);

        HttpResponse<String> resp = sendJson("POST", "/v1/users", body);
        ensureSuccess(resp, 200, 201);

        System.out.printf("Created user: %s (%s)%n", name, email);
    }

    private void listAndDisplayUsers() throws Exception {
        HttpResponse<String> resp = sendJson("GET", "/v1/users", null);
        ensureSuccess(resp, 200);

        JsonNode users = parseJson(resp.body());
        if (!users.isArray()) {
            throw new IllegalStateException("Expected an array from GET /v1/users, got: " + resp.body());
        }

        for (JsonNode user : users) {
            String name = user.path("name").asText("");
            String role = user.hasNonNull("role")
                    ? user.get("role").asText("")
                    : user.path("role_uids").toString();
            String email = user.path("email").asText("");

            System.out.printf("%s | %s | %s%n", name, role, email);
        }
    }

    private void deleteDatabaseWithRetry(long uid) throws Exception {
        long deadlineMs = System.currentTimeMillis() + 60_000L;

        while (System.currentTimeMillis() < deadlineMs) {
            HttpResponse<String> resp = sendJson("DELETE", "/v1/bdbs/" + uid, null);

            if (resp.statusCode() == 200 || resp.statusCode() == 204) {
                return;
            }

            // Database may still be becoming active; Redis can return 409 while busy or not ready.
            if (resp.statusCode() == 409) {
                Thread.sleep(2000);
                continue;
            }

            throw new IllegalStateException(
                    "Failed to delete database uid=" + uid + " status=" + resp.statusCode() + " body=" + resp.body());
        }

        throw new IllegalStateException("Timed out waiting to delete database uid=" + uid);
    }

    private HttpResponse<String> sendJson(String method, String path, JsonNode body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE_URL + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", basicAuthHeader)
                .header("Accept", "application/json");

        if (body != null) {
            builder.header("Content-Type", "application/json");
        }

        HttpRequest request;
        switch (method) {
            case "GET":
                request = builder.GET().build();
                break;
            case "POST":
                request = builder.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body))).build();
                break;
            case "DELETE":
                request = builder.DELETE().build();
                break;
            default:
                throw new IllegalArgumentException("Unsupported method: " + method);
        }

        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void ensureSuccess(HttpResponse<String> resp, int... allowed) {
        for (int code : allowed) {
            if (resp.statusCode() == code) {
                return;
            }
        }
        throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + resp.body());
    }

    private static JsonNode parseJson(String body) throws Exception {
        return MAPPER.readTree(body);
    }

    private static String getenv(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}