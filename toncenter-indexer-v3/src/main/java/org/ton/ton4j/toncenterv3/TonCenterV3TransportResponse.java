package org.ton.ton4j.toncenterv3;

/** Raw Toncenter-compatible response returned by a custom transport. */
public final class TonCenterV3TransportResponse {

  private final int statusCode;
  private final String message;
  private final String body;

  public TonCenterV3TransportResponse(int statusCode, String body) {
    this(statusCode, "", body);
  }

  public TonCenterV3TransportResponse(int statusCode, String message, String body) {
    this.statusCode = statusCode;
    this.message = message == null ? "" : message;
    this.body = body == null ? "" : body;
  }

  public int getStatusCode() {
    return statusCode;
  }

  public String getMessage() {
    return message;
  }

  public String getBody() {
    return body;
  }
}
