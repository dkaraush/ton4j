package org.ton.ton4j.toncenterv3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.ton.ton4j.toncenterv3.model.ResponseModels.AccountStatesResponse;

public class TonCenterV3TransportTest {

  @Test
  public void customTransportReceivesProtocolNeutralRequest() {
    AtomicReference<TonCenterV3TransportRequest> captured = new AtomicReference<>();
    TonCenterV3 client =
        TonCenterV3.builder()
            .testnet()
            .apiKey("test-key")
            .transport(
                request -> {
                  captured.set(request);
                  return new TonCenterV3TransportResponse(200, "{\"accounts\":[]}");
                })
            .build();

    AccountStatesResponse response =
        client.getAccountStates(Arrays.asList("address-1", "address-2"), true);

    assertNotNull(response);
    assertEquals("GET", captured.get().getMethod());
    assertEquals("/accountStates", captured.get().getPath());
    assertEquals(
        Arrays.asList("address-1", "address-2"),
        captured.get().getQuery().get("address"));
    assertEquals(Arrays.asList("true"), captured.get().getQuery().get("include_boc"));
    assertEquals("test-key", captured.get().getHeaders().get("X-API-Key"));
  }
}
