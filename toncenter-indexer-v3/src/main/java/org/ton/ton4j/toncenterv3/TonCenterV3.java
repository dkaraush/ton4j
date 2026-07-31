package org.ton.ton4j.toncenterv3;

import static java.util.Objects.nonNull;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.provider.SendResponse;
import org.ton.ton4j.provider.TonProvider;
import org.ton.ton4j.tlb.AccountStates;
import org.ton.ton4j.tlb.CurrencyCollection;
import org.ton.ton4j.tlb.Message;
import org.ton.ton4j.tlb.Transaction;
import org.ton.ton4j.utils.Utils;
import org.ton.ton4j.toncenterv3.model.ResponseModels.*;

/**
 * TonCenter API v3 client wrapper using OkHttp for HTTP requests and Gson for JSON serialization.
 * Provides synchronous methods for all TonCenter V3 API endpoints. Thread-safe implementation using
 * OkHttp's connection pooling.
 */
@Slf4j
public class TonCenterV3 implements TonProvider {

  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

  private String apiKey;
  private String endpoint;
  private Network network;
  private Duration connectTimeout;
  private Duration readTimeout;
  private Duration writeTimeout;
  private boolean debug;
  private boolean uniqueRequests;
  private Duration confirmationTimeout;
  private Duration confirmationPollInterval;
  private TonCenterV3Transport transport;
  private OkHttpClient httpClient;
  private Gson gson;

  // Private constructor for builder pattern
  private TonCenterV3() {}

  public static class TonCenterV3Builder {
    private String apiKey;
    private String endpoint = Network.MAINNET.getEndpoint();
    private Network network;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(30);
    private Duration writeTimeout = Duration.ofSeconds(30);
    private boolean debug;
    private boolean uniqueRequests;
    private Duration confirmationTimeout = Duration.ofSeconds(60);
    private Duration confirmationPollInterval = Duration.ofSeconds(1);
    private TonCenterV3Transport transport;

    public TonCenterV3Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    public TonCenterV3Builder endpoint(String endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    public TonCenterV3Builder connectTimeout(Duration connectTimeout) {
      this.connectTimeout = connectTimeout;
      return this;
    }

    public TonCenterV3Builder readTimeout(Duration readTimeout) {
      this.readTimeout = readTimeout;
      return this;
    }

    public TonCenterV3Builder writeTimeout(Duration writeTimeout) {
      this.writeTimeout = writeTimeout;
      return this;
    }

    public TonCenterV3Builder network(Network network) {
      this.network = network;
      if (this.endpoint == null
          || this.endpoint.equals(Network.MAINNET.getEndpoint())
          || this.endpoint.equals(Network.TESTNET.getEndpoint())) {
        this.endpoint = network.getEndpoint();
      }
      return this;
    }

    public TonCenterV3Builder mainnet() {
      return network(Network.MAINNET);
    }

    public TonCenterV3Builder testnet() {
      return network(Network.TESTNET);
    }

    public TonCenterV3Builder mylocalton() {
      return network(Network.MY_LOCAL_TON);
    }

    public TonCenterV3Builder debug() {
      this.debug = true;
      return this;
    }

    public TonCenterV3Builder uniqueRequests() {
      this.uniqueRequests = true;
      return this;
    }

    public TonCenterV3Builder confirmationTimeout(Duration confirmationTimeout) {
      this.confirmationTimeout = requirePositive(confirmationTimeout, "confirmationTimeout");
      return this;
    }

    public TonCenterV3Builder confirmationPollInterval(Duration confirmationPollInterval) {
      this.confirmationPollInterval =
          requirePositive(confirmationPollInterval, "confirmationPollInterval");
      return this;
    }

    private static Duration requirePositive(Duration duration, String name) {
      Objects.requireNonNull(duration, name);
      if (duration.isZero() || duration.isNegative()) {
        throw new IllegalArgumentException(name + " must be positive");
      }
      return duration;
    }

    /**
     * Routes every Toncenter request through a protocol-neutral application transport instead of
     * OkHttp.
     */
    public TonCenterV3Builder transport(TonCenterV3Transport transport) {
      this.transport = Objects.requireNonNull(transport, "transport");
      return this;
    }

    public TonCenterV3 build() {
      if (nonNull(this.network)) {
        this.endpoint = this.network.getEndpoint();
      }

      TonCenterV3 tonCenterV3 = new TonCenterV3();
      tonCenterV3.apiKey = this.apiKey;
      tonCenterV3.endpoint = this.endpoint;
      tonCenterV3.network = this.network;
      tonCenterV3.debug = this.debug;
      tonCenterV3.uniqueRequests = this.uniqueRequests;
      tonCenterV3.confirmationTimeout = this.confirmationTimeout;
      tonCenterV3.confirmationPollInterval = this.confirmationPollInterval;
      tonCenterV3.transport = this.transport;
      tonCenterV3.connectTimeout = this.connectTimeout;
      tonCenterV3.readTimeout = this.readTimeout;
      tonCenterV3.writeTimeout = this.writeTimeout;
      tonCenterV3.initializeClients();
      return tonCenterV3;
    }
  }

  public static TonCenterV3Builder builder() {
    return new TonCenterV3Builder();
  }

  private void initializeClients() {
    this.gson =
        new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(
                Integer.class,
                (com.google.gson.JsonDeserializer<Integer>)
                    (json, typeOfT, context) -> {
                      try {
                        String value = json.getAsString();
                        if (value.startsWith("0x") || value.startsWith("0X")) {
                          // Parse hex string
                          return Integer.parseInt(value.substring(2), 16);
                        }
                        return json.getAsInt();
                      } catch (Exception e) {
                        // If it's already a number, return it
                        try {
                          return json.getAsInt();
                        } catch (Exception ex) {
                          return null;
                        }
                      }
                    })
            .create();

    if (nonNull(transport)) {
      return;
    }

    OkHttpClient.Builder clientBuilder =
        new OkHttpClient.Builder()
            .connectTimeout(connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .writeTimeout(writeTimeout.toMillis(), TimeUnit.MILLISECONDS);

    if (nonNull(apiKey) && !apiKey.trim().isEmpty()) {
      clientBuilder.addInterceptor(
          chain -> {
            Request original = chain.request();
            Request.Builder requestBuilder = original.newBuilder().header("X-API-Key", apiKey);
            return chain.proceed(requestBuilder.build());
          });
    }

    clientBuilder.addInterceptor(
        chain -> {
          Request request = chain.request();
          Response response = chain.proceed(request);
          if (debug) {
            log.info("HTTP {} {} -> {}", request.method(), request.url(), response.code());
          }
          return response;
        });

    this.httpClient = clientBuilder.build();
  }

  private <T> T executeGet(String path, Map<String, Object> params, Type responseType) {
    Map<String, List<String>> query = toQuery(params);

    addCommonQueryParameters(query);
    if (nonNull(transport)) {
      return executeTransport("GET", path, query, null, responseType);
    }

    HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse(endpoint + path)).newBuilder();
    addQueryParameters(urlBuilder, query);
    Request request = new Request.Builder().url(urlBuilder.build()).get().build();
    return executeRequest(request, responseType);
  }

  private <T> T executePost(String path, Object requestBody, Type responseType) {
    String json = gson.toJson(requestBody);
    Map<String, List<String>> query = new LinkedHashMap<>();
    addCommonQueryParameters(query);
    if (nonNull(transport)) {
      return executeTransport("POST", path, query, json, responseType);
    }

    RequestBody body = RequestBody.create(json, JSON);
    HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse(endpoint + path)).newBuilder();
    addQueryParameters(urlBuilder, query);
    Request request = new Request.Builder().url(urlBuilder.build()).post(body).build();
    return executeRequest(request, responseType);
  }

  private <T> T executeRequest(Request request, Type responseType) {
    try (Response response = httpClient.newCall(request).execute()) {
      String responseBody = response.body().string();
      return parseResponse(response.code(), response.message(), responseBody, responseType);
    } catch (IOException e) {
      throw new TonCenterException("Network error: " + e.getMessage(), e);
    } catch (Exception e) {
      if (e instanceof TonCenterException) {
        throw e;
      }
      throw new TonCenterException("Unexpected error: " + e.getMessage(), e);
    }
  }

  private <T> T executeTransport(
      String method,
      String path,
      Map<String, List<String>> query,
      String body,
      Type responseType) {
    Map<String, String> headers = new LinkedHashMap<>();
    if (nonNull(apiKey) && !apiKey.trim().isEmpty()) {
      headers.put("X-API-Key", apiKey);
    }
    if (nonNull(body)) {
      headers.put("Content-Type", JSON.toString());
    }

    TonCenterV3TransportRequest request =
        new TonCenterV3TransportRequest(network, method, path, query, headers, body);
    try {
      TonCenterV3TransportResponse response =
          Objects.requireNonNull(transport.execute(request), "transport response");
      return parseResponse(
          response.getStatusCode(), response.getMessage(), response.getBody(), responseType);
    } catch (IOException e) {
      throw new TonCenterException("Transport error: " + e.getMessage(), e);
    } catch (Exception e) {
      if (e instanceof TonCenterException) {
        throw e;
      }
      throw new TonCenterException("Unexpected transport error: " + e.getMessage(), e);
    }
  }

  private <T> T parseResponse(
      int statusCode, String statusMessage, String responseBody, Type responseType) {
    if (statusCode < 200 || statusCode >= 300) {
      try {
        Type errorResponseType = new TypeToken<Map<String, Object>>() {}.getType();
        Map<String, Object> errorResponse = gson.fromJson(responseBody, errorResponseType);

        if (nonNull(errorResponse) && errorResponse.containsKey("error")) {
          Integer errorCode =
              errorResponse.containsKey("code")
                  ? ((Number) errorResponse.get("code")).intValue()
                  : statusCode;
          throw new TonCenterApiException(errorResponse.get("error").toString(), errorCode);
        }
      } catch (TonCenterApiException e) {
        throw e;
      } catch (Exception parseException) {
        log.debug("Could not parse error response: {}", parseException.getMessage());
      }

      String errorMessage = "Toncenter request failed: " + statusCode;
      if (nonNull(responseBody) && !responseBody.trim().isEmpty()) {
        errorMessage += " - " + responseBody;
      } else if (nonNull(statusMessage) && !statusMessage.trim().isEmpty()) {
        errorMessage += " " + statusMessage;
      }
      throw new TonCenterException(errorMessage);
    }

    return gson.fromJson(responseBody, responseType);
  }

  private Map<String, List<String>> toQuery(Map<String, Object> params) {
    Map<String, List<String>> query = new LinkedHashMap<>();
    if (nonNull(params)) {
      for (Map.Entry<String, Object> entry : params.entrySet()) {
        Object value = entry.getValue();
        if (value instanceof List) {
          List<String> values = new ArrayList<>();
          for (Object item : (List<?>) value) {
            if (nonNull(item)) {
              values.add(item.toString());
            }
          }
          query.put(entry.getKey(), values);
        } else if (nonNull(value)) {
          query.put(entry.getKey(), new ArrayList<>(Collections.singletonList(value.toString())));
        }
      }
    }
    return query;
  }

  private void addCommonQueryParameters(Map<String, List<String>> query) {
    if (nonNull(apiKey) && !apiKey.trim().isEmpty()) {
      query.put("api_key", new ArrayList<>(Collections.singletonList(apiKey)));
    }
    if (uniqueRequests) {
      query.put(
          "t", new ArrayList<>(Collections.singletonList(UUID.randomUUID().toString())));
    }
  }

  private void addQueryParameters(
      HttpUrl.Builder urlBuilder, Map<String, List<String>> query) {
    for (Map.Entry<String, List<String>> entry : query.entrySet()) {
      for (String value : entry.getValue()) {
        urlBuilder.addQueryParameter(entry.getKey(), value);
      }
    }
  }

  // ========== ACCOUNT METHODS ==========

  public AccountStatesResponse getAccountStates(List<String> addresses, Boolean includeBoc) {
    Map<String, Object> params = new HashMap<>();
    params.put("address", addresses);
    if (nonNull(includeBoc)) params.put("include_boc", includeBoc);

    Type responseType = new TypeToken<AccountStatesResponse>() {}.getType();
    return executeGet("/accountStates", params, responseType);
  }

  public AccountStatesResponse getAccountStates(List<String> addresses) {
    return getAccountStates(addresses, null);
  }

  public Map<String, Map<String, Object>> getAddressBook(List<String> addresses) {
    Map<String, Object> params = new HashMap<>();
    params.put("address", addresses);

    Type responseType = new TypeToken<Map<String, Map<String, Object>>>() {}.getType();
    return executeGet("/addressBook", params, responseType);
  }

  public V2AddressInformation getAddressInformation(String address, Boolean useV2) {
    Map<String, Object> params = new HashMap<>();
    params.put("address", address);
    if (nonNull(useV2)) params.put("use_v2", useV2);

    Type responseType = new TypeToken<V2AddressInformation>() {}.getType();
    return executeGet("/addressInformation", params, responseType);
  }

  public V2AddressInformation getAddressInformation(String address) {
    return getAddressInformation(address, null);
  }

  public Map<String, Map<String, Object>> getMetadata(List<String> addresses) {
    Map<String, Object> params = new HashMap<>();
    params.put("address", addresses);

    Type responseType = new TypeToken<Map<String, Map<String, Object>>>() {}.getType();
    return executeGet("/metadata", params, responseType);
  }

  public V2WalletInformation getWalletInformation(String address, Boolean useV2) {
    Map<String, Object> params = new HashMap<>();
    params.put("address", address);
    if (nonNull(useV2)) params.put("use_v2", useV2);

    Type responseType = new TypeToken<V2WalletInformation>() {}.getType();
    return executeGet("/walletInformation", params, responseType);
  }

  public V2WalletInformation getWalletInformation(String address) {
    return getWalletInformation(address, null);
  }

  public WalletStatesResponse getWalletStates(List<String> addresses) {
    Map<String, Object> params = new HashMap<>();
    params.put("address", addresses);

    Type responseType = new TypeToken<WalletStatesResponse>() {}.getType();
    return executeGet("/walletStates", params, responseType);
  }

  // ========== ACTION METHODS ==========

  public ActionsResponse getActions(
      String account,
      List<String> txHash,
      List<String> msgHash,
      List<String> actionId,
      List<String> traceId,
      Integer mcSeqno,
      Integer startUtime,
      Integer endUtime,
      Integer startLt,
      Integer endLt,
      List<String> actionType,
      List<String> excludeActionType,
      List<String> supportedActionTypes,
      Boolean includeAccounts,
      Boolean includeTransactions,
      Integer limit,
      Integer offset,
      String sort) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(account)) params.put("account", account);
    if (nonNull(txHash)) params.put("tx_hash", txHash);
    if (nonNull(msgHash)) params.put("msg_hash", msgHash);
    if (nonNull(actionId)) params.put("action_id", actionId);
    if (nonNull(traceId)) params.put("trace_id", traceId);
    if (nonNull(mcSeqno)) params.put("mc_seqno", mcSeqno);
    if (nonNull(startUtime)) params.put("start_utime", startUtime);
    if (nonNull(endUtime)) params.put("end_utime", endUtime);
    if (nonNull(startLt)) params.put("start_lt", startLt);
    if (nonNull(endLt)) params.put("end_lt", endLt);
    if (nonNull(actionType)) params.put("action_type", actionType);
    if (nonNull(excludeActionType)) params.put("exclude_action_type", excludeActionType);
    if (nonNull(supportedActionTypes)) params.put("supported_action_types", supportedActionTypes);
    if (nonNull(includeAccounts)) params.put("include_accounts", includeAccounts);
    if (nonNull(includeTransactions)) params.put("include_transactions", includeTransactions);
    if (nonNull(limit)) params.put("limit", limit);
    if (nonNull(offset)) params.put("offset", offset);
    if (nonNull(sort)) params.put("sort", sort);

    Type responseType = new TypeToken<ActionsResponse>() {}.getType();
    return executeGet("/actions", params, responseType);
  }

  public ActionsResponse getPendingActions(
      String account,
      List<String> extMsgHash,
      List<String> supportedActionTypes,
      Boolean includeTransactions) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(account)) params.put("account", account);
    if (nonNull(extMsgHash)) params.put("ext_msg_hash", extMsgHash);
    if (nonNull(supportedActionTypes)) params.put("supported_action_types", supportedActionTypes);
    if (nonNull(includeTransactions)) params.put("include_transactions", includeTransactions);

    Type responseType = new TypeToken<ActionsResponse>() {}.getType();
    return executeGet("/pendingActions", params, responseType);
  }

  public TracesResponse getTraces(
      String account,
      List<String> traceId,
      List<String> txHash,
      List<String> msgHash,
      Integer mcSeqno,
      Integer startUtime,
      Integer endUtime,
      Integer startLt,
      Integer endLt,
      Boolean includeActions,
      List<String> supportedActionTypes,
      Integer limit,
      Integer offset,
      String sort) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(account)) params.put("account", account);
    if (nonNull(traceId)) params.put("trace_id", traceId);
    if (nonNull(txHash)) params.put("tx_hash", txHash);
    if (nonNull(msgHash)) params.put("msg_hash", msgHash);
    if (nonNull(mcSeqno)) params.put("mc_seqno", mcSeqno);
    if (nonNull(startUtime)) params.put("start_utime", startUtime);
    if (nonNull(endUtime)) params.put("end_utime", endUtime);
    if (nonNull(startLt)) params.put("start_lt", startLt);
    if (nonNull(endLt)) params.put("end_lt", endLt);
    if (nonNull(includeActions)) params.put("include_actions", includeActions);
    if (nonNull(supportedActionTypes)) params.put("supported_action_types", supportedActionTypes);
    if (nonNull(limit)) params.put("limit", limit);
    if (nonNull(offset)) params.put("offset", offset);
    if (nonNull(sort)) params.put("sort", sort);

    Type responseType = new TypeToken<TracesResponse>() {}.getType();
    return executeGet("/traces", params, responseType);
  }

  public TracesResponse getPendingTraces(String account, List<String> extMsgHash) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(account)) params.put("account", account);
    if (nonNull(extMsgHash)) params.put("ext_msg_hash", extMsgHash);

    Type responseType = new TypeToken<TracesResponse>() {}.getType();
    return executeGet("/pendingTraces", params, responseType);
  }

  // ========== BLOCKCHAIN METHODS ==========

  public BlocksResponse getBlocks(
      Integer workchain,
      String shard,
      Integer seqno,
      String rootHash,
      String fileHash,
      Integer mcSeqno,
      Integer startUtime,
      Integer endUtime,
      Integer startLt,
      Integer endLt,
      Integer limit,
      Integer offset,
      String sort) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(workchain)) params.put("workchain", workchain);
    if (nonNull(shard)) params.put("shard", shard);
    if (nonNull(seqno)) params.put("seqno", seqno);
    if (nonNull(rootHash)) params.put("root_hash", rootHash);
    if (nonNull(fileHash)) params.put("file_hash", fileHash);
    if (nonNull(mcSeqno)) params.put("mc_seqno", mcSeqno);
    if (nonNull(startUtime)) params.put("start_utime", startUtime);
    if (nonNull(endUtime)) params.put("end_utime", endUtime);
    if (nonNull(startLt)) params.put("start_lt", startLt);
    if (nonNull(endLt)) params.put("end_lt", endLt);
    if (nonNull(limit)) params.put("limit", limit);
    if (nonNull(offset)) params.put("offset", offset);
    if (nonNull(sort)) params.put("sort", sort);

    Type responseType = new TypeToken<BlocksResponse>() {}.getType();
    return executeGet("/blocks", params, responseType);
  }

  public TransactionsResponse getTransactions(
      Integer workchain,
      String shard,
      Integer seqno,
      Integer mcSeqno,
      List<String> account,
      List<String> excludeAccount,
      String hash,
      Long lt,
      Integer startUtime,
      Integer endUtime,
      Integer startLt,
      Integer endLt,
      Integer limit,
      Integer offset,
      String sort) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(workchain)) params.put("workchain", workchain);
    if (nonNull(shard)) params.put("shard", shard);
    if (nonNull(seqno)) params.put("seqno", seqno);
    if (nonNull(mcSeqno)) params.put("mc_seqno", mcSeqno);
    if (nonNull(account)) params.put("account", account);
    if (nonNull(excludeAccount)) params.put("exclude_account", excludeAccount);
    if (nonNull(hash)) params.put("hash", hash);
    if (nonNull(lt)) params.put("lt", lt);
    if (nonNull(startUtime)) params.put("start_utime", startUtime);
    if (nonNull(endUtime)) params.put("end_utime", endUtime);
    if (nonNull(startLt)) params.put("start_lt", startLt);
    if (nonNull(endLt)) params.put("end_lt", endLt);
    if (nonNull(limit)) params.put("limit", limit);
    if (nonNull(offset)) params.put("offset", offset);
    if (nonNull(sort)) params.put("sort", sort);

    Type responseType = new TypeToken<TransactionsResponse>() {}.getType();
    return executeGet("/transactions", params, responseType);
  }

  public TransactionsResponse getPendingTransactions(
      List<String> account, List<String> traceId) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(account)) params.put("account", account);
    if (nonNull(traceId)) params.put("trace_id", traceId);

    Type responseType = new TypeToken<TransactionsResponse>() {}.getType();
    return executeGet("/pendingTransactions", params, responseType);
  }

  public MessagesResponse getMessages(
      List<String> msgHash,
      String bodyHash,
      String source,
      String destination,
      String opcode,
      Integer startUtime,
      Integer endUtime,
      Integer startLt,
      Integer endLt,
      String direction,
      Boolean excludeExternals,
      Boolean onlyExternals,
      Integer limit,
      Integer offset,
      String sort) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(msgHash)) params.put("msg_hash", msgHash);
    if (nonNull(bodyHash)) params.put("body_hash", bodyHash);
    if (nonNull(source)) params.put("source", source);
    if (nonNull(destination)) params.put("destination", destination);
    if (nonNull(opcode)) params.put("opcode", opcode);
    if (nonNull(startUtime)) params.put("start_utime", startUtime);
    if (nonNull(endUtime)) params.put("end_utime", endUtime);
    if (nonNull(startLt)) params.put("start_lt", startLt);
    if (nonNull(endLt)) params.put("end_lt", endLt);
    if (nonNull(direction)) params.put("direction", direction);
    if (nonNull(excludeExternals)) params.put("exclude_externals", excludeExternals);
    if (nonNull(onlyExternals)) params.put("only_externals", onlyExternals);
    if (nonNull(limit)) params.put("limit", limit);
    if (nonNull(offset)) params.put("offset", offset);
    if (nonNull(sort)) params.put("sort", sort);

    Type responseType = new TypeToken<MessagesResponse>() {}.getType();
    return executeGet("/messages", params, responseType);
  }

  public MasterchainInfo getMasterchainInfo() {
    Type responseType = new TypeToken<MasterchainInfo>() {}.getType();
    return executeGet("/masterchainInfo", null, responseType);
  }

  public TransactionsResponse getMasterchainBlockShards(
      Integer seqno, Integer limit, Integer offset) {
    Map<String, Object> params = new HashMap<>();
    params.put("seqno", seqno);
    if (nonNull(limit)) params.put("limit", limit);
    if (nonNull(offset)) params.put("offset", offset);

    Type responseType = new TypeToken<TransactionsResponse>() {}.getType();
    return executeGet("/masterchainBlockShards", params, responseType);
  }

  public TransactionsResponse getMasterchainBlockShardState(Integer seqno) {
    Map<String, Object> params = new HashMap<>();
    params.put("seqno", seqno);

    Type responseType = new TypeToken<TransactionsResponse>() {}.getType();
    return executeGet("/masterchainBlockShardState", params, responseType);
  }

  public TransactionsResponse getAdjacentTransactions(String hash, String direction) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(hash)) params.put("hash", hash);
    if (nonNull(direction)) params.put("direction", direction);

    Type responseType = new TypeToken<TransactionsResponse>() {}.getType();
    return executeGet("/adjacentTransactions", params, responseType);
  }

  public TransactionsResponse getTransactionsByMasterchainBlock(
      Integer seqno, Integer limit, Integer offset, String sort) {
    Map<String, Object> params = new HashMap<>();
    params.put("seqno", seqno);
    if (nonNull(limit)) params.put("limit", limit);
    if (nonNull(offset)) params.put("offset", offset);
    if (nonNull(sort)) params.put("sort", sort);

    Type responseType = new TypeToken<TransactionsResponse>() {}.getType();
    return executeGet("/transactionsByMasterchainBlock", params, responseType);
  }

  public TransactionsResponse getTransactionsByMessage(
      String msgHash,
      String bodyHash,
      String opcode,
      String direction,
      Integer limit,
      Integer offset) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(msgHash)) params.put("msg_hash", msgHash);
    if (nonNull(bodyHash)) params.put("body_hash", bodyHash);
    if (nonNull(opcode)) params.put("opcode", opcode);
    if (nonNull(direction)) params.put("direction", direction);
    if (nonNull(limit)) params.put("limit", limit);
    if (nonNull(offset)) params.put("offset", offset);

    Type responseType = new TypeToken<TransactionsResponse>() {}.getType();
    return executeGet("/transactionsByMessage", params, responseType);
  }

  // ========== JETTON METHODS ==========

  public JettonMastersResponse getJettonMasters(
      List<String> address, List<String> adminAddress, Integer limit, Integer offset) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(address)) params.put("address", address);
    if (nonNull(adminAddress)) params.put("admin_address", adminAddress);
    if (nonNull(limit)) params.put("limit", limit);
    if (nonNull(offset)) params.put("offset", offset);

    Type responseType = new TypeToken<JettonMastersResponse>() {}.getType();
    return executeGet("/jetton/masters", params, responseType);
  }

  public JettonWalletsResponse getJettonWallets(
      List<String> address,
      List<String> ownerAddress,
      List<String> jettonAddress,
      Boolean excludeZeroBalance,
      Integer limit,
      Integer offset,
      String sort) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(address)) params.put("address", address);
    if (nonNull(ownerAddress)) params.put("owner_address", ownerAddress);
    if (nonNull(jettonAddress)) params.put("jetton_address", jettonAddress);
    if (nonNull(excludeZeroBalance)) params.put("exclude_zero_balance", excludeZeroBalance);
    if (nonNull(limit)) params.put("limit", limit);
    if (nonNull(offset)) params.put("offset", offset);
    if (nonNull(sort)) params.put("sort", sort);

    Type responseType = new TypeToken<JettonWalletsResponse>() {}.getType();
    return executeGet("/jetton/wallets", params, responseType);
  }

  public JettonTransfersResponse getJettonTransfers(
      List<String> ownerAddress,
      List<String> jettonWallet,
      String jettonMaster,
      String direction,
      Integer startUtime,
      Integer endUtime,
      Integer startLt,
      Integer endLt,
      Integer limit,
      Integer offset,
      String sort) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(ownerAddress)) params.put("owner_address", ownerAddress);
    if (nonNull(jettonWallet)) params.put("jetton_wallet", jettonWallet);
    if (nonNull(jettonMaster)) params.put("jetton_master", jettonMaster);
    if (nonNull(direction)) params.put("direction", direction);
    if (nonNull(startUtime)) params.put("start_utime", startUtime);
    if (nonNull(endUtime)) params.put("end_utime", endUtime);
    if (nonNull(startLt)) params.put("start_lt", startLt);
    if (nonNull(endLt)) params.put("end_lt", endLt);
    if (nonNull(limit)) params.put("limit", limit);
    if (nonNull(offset)) params.put("offset", offset);
    if (nonNull(sort)) params.put("sort", sort);

    Type responseType = new TypeToken<JettonTransfersResponse>() {}.getType();
    return executeGet("/jetton/transfers", params, responseType);
  }

  public JettonBurnsResponse getJettonBurns(
      List<String> address,
      List<String> jettonWallet,
      String jettonMaster,
      Integer startUtime,
      Integer endUtime,
      Integer startLt,
      Integer endLt,
      Integer limit,
      Integer offset,
      String sort) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(address)) params.put("address", address);
    if (nonNull(jettonWallet)) params.put("jetton_wallet", jettonWallet);
    if (nonNull(jettonMaster)) params.put("jetton_master", jettonMaster);
    if (nonNull(startUtime)) params.put("start_utime", startUtime);
    if (nonNull(endUtime)) params.put("end_utime", endUtime);
    if (nonNull(startLt)) params.put("start_lt", startLt);
    if (nonNull(endLt)) params.put("end_lt", endLt);
    if (nonNull(limit)) params.put("limit", limit);
    if (nonNull(offset)) params.put("offset", offset);
    if (nonNull(sort)) params.put("sort", sort);

    Type responseType = new TypeToken<JettonBurnsResponse>() {}.getType();
    return executeGet("/jetton/burns", params, responseType);
  }

  // ========== NFT METHODS ==========

  public NFTCollectionsResponse getNFTCollections(
      List<String> collectionAddress, List<String> ownerAddress, Integer limit, Integer offset) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(collectionAddress)) params.put("collection_address", collectionAddress);
    if (nonNull(ownerAddress)) params.put("owner_address", ownerAddress);
    if (nonNull(limit)) params.put("limit", limit);
    if (nonNull(offset)) params.put("offset", offset);

    Type responseType = new TypeToken<NFTCollectionsResponse>() {}.getType();
    return executeGet("/nft/collections", params, responseType);
  }

  public NFTItemsResponse getNFTItems(
      List<String> address,
      List<String> ownerAddress,
      List<String> collectionAddress,
      List<String> index,
      Boolean sortByLastTransactionLt,
      Integer limit,
      Integer offset) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(address)) params.put("address", address);
    if (nonNull(ownerAddress)) params.put("owner_address", ownerAddress);
    if (nonNull(collectionAddress)) params.put("collection_address", collectionAddress);
    if (nonNull(index)) params.put("index", index);
    if (nonNull(sortByLastTransactionLt))
      params.put("sort_by_last_transaction_lt", sortByLastTransactionLt);
    if (nonNull(limit)) params.put("limit", limit);
    if (nonNull(offset)) params.put("offset", offset);

    Type responseType = new TypeToken<NFTItemsResponse>() {}.getType();
    return executeGet("/nft/items", params, responseType);
  }

  public NFTTransfersResponse getNFTTransfers(
      List<String> ownerAddress,
      List<String> itemAddress,
      String collectionAddress,
      String direction,
      Integer startUtime,
      Integer endUtime,
      Integer startLt,
      Integer endLt,
      Integer limit,
      Integer offset,
      String sort) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(ownerAddress)) params.put("owner_address", ownerAddress);
    if (nonNull(itemAddress)) params.put("item_address", itemAddress);
    if (nonNull(collectionAddress)) params.put("collection_address", collectionAddress);
    if (nonNull(direction)) params.put("direction", direction);
    if (nonNull(startUtime)) params.put("start_utime", startUtime);
    if (nonNull(endUtime)) params.put("end_utime", endUtime);
    if (nonNull(startLt)) params.put("start_lt", startLt);
    if (nonNull(endLt)) params.put("end_lt", endLt);
    if (nonNull(limit)) params.put("limit", limit);
    if (nonNull(offset)) params.put("offset", offset);
    if (nonNull(sort)) params.put("sort", sort);

    Type responseType = new TypeToken<NFTTransfersResponse>() {}.getType();
    return executeGet("/nft/transfers", params, responseType);
  }

  // ========== DNS METHODS ==========

  public DNSRecordsResponse getDNSRecords(
      String wallet, String domain, Integer limit, Integer offset) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(wallet)) params.put("wallet", wallet);
    if (nonNull(domain)) params.put("domain", domain);
    if (nonNull(limit)) params.put("limit", limit);
    if (nonNull(offset)) params.put("offset", offset);

    Type responseType = new TypeToken<DNSRecordsResponse>() {}.getType();
    return executeGet("/dns/records", params, responseType);
  }

  // ========== MULTISIG METHODS ==========

  public MultisigResponse getMultisigWallets(
      List<String> multisigAddress,
      List<String> walletAddress,
      Integer limit,
      Integer offset,
      String sort,
      Boolean includeOrders) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(multisigAddress)) params.put("address", multisigAddress);
    if (nonNull(walletAddress)) params.put("wallet_address", walletAddress);
    if (nonNull(limit)) params.put("limit", limit);
    if (nonNull(offset)) params.put("offset", offset);
    if (nonNull(sort)) params.put("sort", sort);
    if (nonNull(includeOrders)) params.put("include_orders", includeOrders);

    Type responseType = new TypeToken<MultisigResponse>() {}.getType();
    return executeGet("/multisig/wallets", params, responseType);
  }

  public MultisigOrderResponse getMultisigOrders(
      List<String> orderAddress,
      List<String> multisigAddress,
      Boolean parseActions,
      Integer limit,
      Integer offset,
      String sort) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(orderAddress)) params.put("address", orderAddress);
    if (nonNull(multisigAddress)) params.put("multisig_address", multisigAddress);
    if (nonNull(parseActions)) params.put("parse_actions", parseActions);
    if (nonNull(limit)) params.put("limit", limit);
    if (nonNull(offset)) params.put("offset", offset);
    if (nonNull(sort)) params.put("sort", sort);

    Type responseType = new TypeToken<MultisigOrderResponse>() {}.getType();
    return executeGet("/multisig/orders", params, responseType);
  }

  // ========== VESTING METHODS ==========

  public VestingContractsResponse getVestingContracts(
      List<String> contractAddress,
      List<String> walletAddress,
      Boolean checkWhitelist,
      Integer limit,
      Integer offset) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(contractAddress)) params.put("contract_address", contractAddress);
    if (nonNull(walletAddress)) params.put("wallet_address", walletAddress);
    if (nonNull(checkWhitelist)) params.put("check_whitelist", checkWhitelist);
    if (nonNull(limit)) params.put("limit", limit);
    if (nonNull(offset)) params.put("offset", offset);

    Type responseType = new TypeToken<VestingContractsResponse>() {}.getType();
    return executeGet("/vesting", params, responseType);
  }

  // ========== STATS METHODS ==========

  public List<AccountBalance> getTopAccountsByBalance(Integer limit, Integer offset) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(limit)) params.put("limit", limit);
    if (nonNull(offset)) params.put("offset", offset);

    Type responseType = new TypeToken<List<AccountBalance>>() {}.getType();
    return executeGet("/topAccountsByBalance", params, responseType);
  }

  // ========== UTILS METHODS ==========

  public DecodeResponse decode(List<String> opcodes, List<String> bodies) {
    Map<String, Object> params = new HashMap<>();
    if (nonNull(opcodes)) params.put("opcodes", opcodes);
    if (nonNull(bodies)) params.put("bodies", bodies);

    Type responseType = new TypeToken<DecodeResponse>() {}.getType();
    return executeGet("/decode", params, responseType);
  }

  public DecodeResponse decodePost(DecodeRequest request) {
    Type responseType = new TypeToken<DecodeResponse>() {}.getType();
    return executePost("/decode", request, responseType);
  }

  // ========== V2 COMPATIBILITY METHODS ==========

  public V2EstimateFeeResult estimateFee(V2EstimateFeeRequest request) {
    Type responseType = new TypeToken<V2EstimateFeeResult>() {}.getType();
    return executePost("/estimateFee", request, responseType);
  }

  public V2SendMessageResult sendMessage(V2SendMessageRequest request) {
    Type responseType = new TypeToken<V2SendMessageResult>() {}.getType();
    return executePost("/message", request, responseType);
  }

  public Map<String, Object> runGetMethod(V2RunGetMethodRequest request) {
    Type responseType = new TypeToken<Map<String, Object>>() {}.getType();
    return executePost("/runGetMethod", request, responseType);
  }

  public V2RunGetMethodResult runGetMethodResult(V2RunGetMethodRequest request) {
    Type responseType = new TypeToken<V2RunGetMethodResult>() {}.getType();
    return executePost("/runGetMethod", request, responseType);
  }

  // ========== TON PROVIDER ==========

  @Override
  public BigInteger getBalance(Address address) {
    AccountStatesResponse response =
        getAccountStates(Collections.singletonList(address.toRaw()), false);
    if (response == null
        || response.getAccounts() == null
        || response.getAccounts().isEmpty()
        || response.getAccounts().get(0).getBalance() == null) {
      return BigInteger.ZERO;
    }
    return parseDecimal(response.getAccounts().get(0).getBalance());
  }

  @Override
  public long getSeqno(Address address) {
    return runGetNumber(address, "seqno").longValue();
  }

  @Override
  public BigInteger getPublicKey(Address address) {
    return runGetNumber(address, "get_public_key");
  }

  @Override
  public long getSubWalletId(Address address) {
    return runGetNumber(address, "get_subwallet_id").longValue();
  }

  @Override
  public boolean isDeployed(Address address) {
    AccountStatesResponse response =
        getAccountStates(Collections.singletonList(address.toRaw()), false);
    return response != null
        && response.getAccounts() != null
        && !response.getAccounts().isEmpty()
        && "active".equalsIgnoreCase(response.getAccounts().get(0).getStatus());
  }

  @Override
  public void waitForDeployment(Address address, int timeoutSeconds) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    while (System.nanoTime() < deadline) {
      if (isDeployed(address)) {
        return;
      }
      sleepForPolling();
    }
    throw new TonCenterException(
        "Timeout waiting for deployment of " + address.toRaw());
  }

  @Override
  public void waitForBalanceChange(Address address, int timeoutSeconds) {
    BigInteger initialBalance = getBalance(address);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    while (System.nanoTime() < deadline) {
      sleepForPolling();
      if (!initialBalance.equals(getBalance(address))) {
        return;
      }
    }
    throw new TonCenterException(
        "Timeout waiting for balance change of " + address.toRaw());
  }

  @Override
  public void printAccountMessages(Address account) {
    printAccountMessages(account, 20);
  }

  @Override
  public void printAccountMessages(Address account, int historyLimit) {
    TransactionsResponse response = getAccountTransactions(account, historyLimit);
    if (response == null || response.getTransactions() == null) {
      return;
    }
    for (org.ton.ton4j.toncenterv3.model.CommonModels.Transaction transaction
        : response.getTransactions()) {
      if (transaction.getInMsg() != null) {
        log.info("in-message {}", transaction.getInMsg());
      }
      if (transaction.getOutMsgs() != null) {
        for (org.ton.ton4j.toncenterv3.model.CommonModels.Message message
            : transaction.getOutMsgs()) {
          log.info("out-message {}", message);
        }
      }
    }
  }

  @Override
  public void printAccountTransactions(Address account) {
    printAccountTransactions(account, 20, false);
  }

  @Override
  public void printAccountTransactions(Address account, int historyLimit) {
    printAccountTransactions(account, historyLimit, false);
  }

  @Override
  public void printAccountTransactions(
      Address account, int historyLimit, boolean withMessages) {
    TransactionsResponse response = getAccountTransactions(account, historyLimit);
    if (response == null || response.getTransactions() == null) {
      return;
    }
    for (org.ton.ton4j.toncenterv3.model.CommonModels.Transaction transaction
        : response.getTransactions()) {
      log.info("transaction {}", transaction);
      if (withMessages) {
        if (transaction.getInMsg() != null) {
          log.info("in-message {}", transaction.getInMsg());
        }
        if (transaction.getOutMsgs() != null) {
          for (org.ton.ton4j.toncenterv3.model.CommonModels.Message message
              : transaction.getOutMsgs()) {
            log.info("out-message {}", message);
          }
        }
      }
    }
  }

  @Override
  public Transaction sendExternalMessageWithConfirmation(Message externalMessage) {
    return toTlbTransaction(sendExternalMessageWithConfirmationV3(externalMessage));
  }

  /**
   * Sends an external message and returns Toncenter's complete indexed V3 transaction model after
   * it appears in the index.
   *
   * <p>The {@link TonProvider} compatibility method converts this response to the legacy TLB
   * transaction type, which cannot carry every indexed V3 field.
   */
  public org.ton.ton4j.toncenterv3.model.CommonModels.Transaction
      sendExternalMessageWithConfirmationV3(Message externalMessage) {
    V2SendMessageResult sent = submitExternalMessage(externalMessage);
    String messageHash =
        nonNull(sent.getMessageHashNorm()) ? sent.getMessageHashNorm() : sent.getMessageHash();
    if (messageHash == null || messageHash.isEmpty()) {
      throw new TonCenterException("Toncenter V3 returned an empty message hash");
    }

    long deadline = System.nanoTime() + confirmationTimeout.toNanos();
    TonCenterException lastError = null;
    while (System.nanoTime() < deadline) {
      try {
        TransactionsResponse response =
            getTransactionsByMessage(messageHash, null, null, null, 1, 0);
        if (response != null
            && response.getTransactions() != null
            && !response.getTransactions().isEmpty()) {
          return response.getTransactions().get(0);
        }
      } catch (TonCenterException e) {
        lastError = e;
      }
      sleepForPolling();
    }
    throw new TonCenterException(
        "Timeout waiting for message " + messageHash,
        lastError);
  }

  @Override
  public SendResponse sendExternalMessage(Message externalMessage) {
    try {
      V2SendMessageResult result = submitExternalMessage(externalMessage);
      String hash =
          nonNull(result.getMessageHashNorm())
              ? result.getMessageHashNorm()
              : result.getMessageHash();
      return SendResponse.builder().code(0).message(hash).build();
    } catch (Exception e) {
      return SendResponse.builder().code(1).message(e.getMessage()).build();
    }
  }

  private V2SendMessageResult submitExternalMessage(Message externalMessage) {
    if (externalMessage == null) {
      throw new IllegalArgumentException("External message is null");
    }
    V2SendMessageRequest request = new V2SendMessageRequest();
    request.setBoc(externalMessage.toCell().toBase64());
    V2SendMessageResult result = sendMessage(request);
    if (result == null) {
      throw new TonCenterException("Toncenter V3 returned an empty send response");
    }
    return result;
  }

  private BigInteger runGetNumber(Address address, String method) {
    V2RunGetMethodRequest request = new V2RunGetMethodRequest();
    request.setAddress(address.toRaw());
    request.setMethod(method);
    request.setStack(Collections.emptyList());
    V2RunGetMethodResult result = runGetMethodResult(request);
    if (result == null || result.getExitCode() == null || result.getExitCode() != 0) {
      throw new TonCenterException(
          method
              + " failed with exit code "
              + (result == null ? "null" : result.getExitCode()));
    }
    if (result.getStack() == null
        || result.getStack().isEmpty()
        || result.getStack().get(0) == null) {
      throw new TonCenterException(method + " returned an empty stack");
    }
    Object value = result.getStack().get(0).getValue();
    if (value == null) {
      throw new TonCenterException(method + " returned an empty value");
    }
    return parseNumber(value.toString());
  }

  private TransactionsResponse getAccountTransactions(Address account, int historyLimit) {
    if (historyLimit < 1) {
      throw new IllegalArgumentException("historyLimit must be positive");
    }
    return getTransactions(
        null,
        null,
        null,
        null,
        Collections.singletonList(account.toRaw()),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Math.min(historyLimit, 1000),
        0,
        "desc");
  }

  private Transaction toTlbTransaction(
      org.ton.ton4j.toncenterv3.model.CommonModels.Transaction transaction) {
    Address account = Address.of(transaction.getAccount());
    return Transaction.builder()
        .magic(0b0111)
        .accountAddr(new BigInteger(1, account.hashPart))
        .lt(parseDecimal(transaction.getLt()))
        .prevTxHash(parseHash(transaction.getPrevTransHash()))
        .prevTxLt(parseDecimal(transaction.getPrevTransLt()))
        .now(transaction.getNow() == null ? 0 : transaction.getNow())
        .outMsgCount(
            transaction.getOutMsgs() == null ? 0 : transaction.getOutMsgs().size())
        .origStatus(parseAccountState(transaction.getOrigStatus()))
        .endStatus(parseAccountState(transaction.getEndStatus()))
        .totalFees(
            CurrencyCollection.builder()
                .coins(parseDecimal(transaction.getTotalFees()))
                .build())
        .build();
  }

  private AccountStates parseAccountState(String state) {
    if ("active".equalsIgnoreCase(state)) {
      return AccountStates.ACTIVE;
    }
    if ("frozen".equalsIgnoreCase(state)) {
      return AccountStates.FROZEN;
    }
    if ("uninit".equalsIgnoreCase(state)
        || "uninitialized".equalsIgnoreCase(state)) {
      return AccountStates.UNINIT;
    }
    return AccountStates.NON_EXIST;
  }

  private BigInteger parseHash(String value) {
    if (value == null || value.isEmpty()) {
      return BigInteger.ZERO;
    }
    try {
      String hex = value.startsWith("0x") || value.startsWith("0X")
          ? value.substring(2)
          : value;
      if (hex.matches("[0-9a-fA-F]{64}")) {
        return new BigInteger(hex, 16);
      }
      return new BigInteger(1, Utils.base64ToBytes(value));
    } catch (Exception e) {
      throw new TonCenterException("Invalid transaction hash: " + value, e);
    }
  }

  private BigInteger parseDecimal(String value) {
    if (value == null || value.isEmpty()) {
      return BigInteger.ZERO;
    }
    try {
      return new BigInteger(value);
    } catch (NumberFormatException e) {
      throw new TonCenterException("Invalid decimal value: " + value, e);
    }
  }

  private BigInteger parseNumber(String value) {
    try {
      if (value.startsWith("-0x") || value.startsWith("-0X")) {
        return new BigInteger(value.substring(3), 16).negate();
      }
      if (value.startsWith("0x") || value.startsWith("0X")) {
        return new BigInteger(value.substring(2), 16);
      }
      return new BigInteger(value);
    } catch (NumberFormatException e) {
      throw new TonCenterException("Invalid stack number: " + value, e);
    }
  }

  private void sleepForPolling() {
    try {
      Thread.sleep(Math.max(1, confirmationPollInterval.toMillis()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TonCenterException("Interrupted while polling Toncenter V3", e);
    }
  }

  // ========== UTILITY METHODS ==========

  public void close() {
    if (nonNull(httpClient)) {
      httpClient.dispatcher().executorService().shutdown();
      httpClient.connectionPool().evictAll();
    }
  }
}
