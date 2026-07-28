package org.ton.ton4j.toncenterv3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A protocol-neutral representation of a Toncenter V3 request. */
public final class TonCenterV3TransportRequest {

  private final Network network;
  private final String method;
  private final String path;
  private final Map<String, List<String>> query;
  private final Map<String, String> headers;
  private final String body;

  TonCenterV3TransportRequest(
      Network network,
      String method,
      String path,
      Map<String, List<String>> query,
      Map<String, String> headers,
      String body) {
    this.network = network;
    this.method = method;
    this.path = path;
    this.query = immutableQuery(query);
    this.headers =
        Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    this.body = body;
  }

  public Network getNetwork() {
    return network;
  }

  public String getMethod() {
    return method;
  }

  public String getPath() {
    return path;
  }

  public Map<String, List<String>> getQuery() {
    return query;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  public String getBody() {
    return body;
  }

  private static Map<String, List<String>> immutableQuery(
      Map<String, List<String>> query) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : query.entrySet()) {
      result.put(
          entry.getKey(),
          Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
    }
    return Collections.unmodifiableMap(result);
  }
}
