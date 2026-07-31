package org.ton.ton4j.toncenterv3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.cell.CellBuilder;
import org.ton.ton4j.provider.SendResponse;
import org.ton.ton4j.tlb.ExternalMessageInInfo;
import org.ton.ton4j.tlb.Message;
import org.ton.ton4j.tlb.MsgAddressIntStd;
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

  @Test
  public void providerReadsSeqnoThroughRunGetMethod() {
    AtomicReference<TonCenterV3TransportRequest> captured = new AtomicReference<>();
    TonCenterV3 client =
        TonCenterV3.builder()
            .testnet()
            .transport(
                request -> {
                  captured.set(request);
                  return new TonCenterV3TransportResponse(
                      200,
                      "{\"gas_used\":913,\"exit_code\":0,"
                          + "\"stack\":[{\"type\":\"num\",\"value\":\"0x2ff\"}]}");
                })
            .build();

    long seqno =
        client.getSeqno(
            Address.of(
                "0:87611fdf6c38e3af576dc8c9a005b1f71d20408123701dd399bc06e090d395da"));

    assertEquals(767L, seqno);
    assertEquals("POST", captured.get().getMethod());
    assertEquals("/runGetMethod", captured.get().getPath());
    assertTrue(captured.get().getBody().contains("\"method\""));
    assertTrue(captured.get().getBody().contains("\"seqno\""));
    assertTrue(captured.get().getBody().contains("\"stack\""));
    assertTrue(captured.get().getBody().contains("[]"));
  }

  @Test
  public void providerSendsExternalMessageThroughV3MessageEndpoint() {
    AtomicReference<TonCenterV3TransportRequest> captured = new AtomicReference<>();
    TonCenterV3 client =
        TonCenterV3.builder()
            .testnet()
            .transport(
                request -> {
                  captured.set(request);
                  return new TonCenterV3TransportResponse(
                      200,
                      "{\"message_hash\":\"hash\",\"message_hash_norm\":\"normalized\"}");
                })
            .build();
    Message message =
        Message.builder()
            .info(
                ExternalMessageInInfo.builder()
                    .dstAddr(
                        MsgAddressIntStd.builder()
                            .workchainId((byte) 0)
                            .address(BigInteger.ONE)
                            .build())
                    .build())
            .body(CellBuilder.beginCell().endCell())
            .build();

    SendResponse response = client.sendExternalMessage(message);

    assertEquals(0L, response.getCode());
    assertEquals("normalized", response.getMessage());
    assertEquals("POST", captured.get().getMethod());
    assertEquals("/message", captured.get().getPath());
    assertTrue(captured.get().getBody().contains("\"boc\":"));
  }

  @Test
  public void v3ConfirmationReturnsIndexedTransaction() {
    AtomicReference<TonCenterV3TransportRequest> confirmationRequest = new AtomicReference<>();
    TonCenterV3 client =
        TonCenterV3.builder()
            .testnet()
            .confirmationPollInterval(Duration.ofMillis(1))
            .transport(
                request -> {
                  if ("/message".equals(request.getPath())) {
                    return new TonCenterV3TransportResponse(
                        200,
                        "{\"message_hash\":\"hash\",\"message_hash_norm\":\"normalized\"}");
                  }
                  confirmationRequest.set(request);
                  return new TonCenterV3TransportResponse(
                      200, "{\"transactions\":[{\"hash\":\"transaction-hash\"}]}");
                })
            .build();
    Message message =
        Message.builder()
            .info(
                ExternalMessageInInfo.builder()
                    .dstAddr(
                        MsgAddressIntStd.builder()
                            .workchainId((byte) 0)
                            .address(BigInteger.ONE)
                            .build())
                    .build())
            .body(CellBuilder.beginCell().endCell())
            .build();

    org.ton.ton4j.toncenterv3.model.CommonModels.Transaction transaction =
        client.sendExternalMessageWithConfirmationV3(message);

    assertEquals("transaction-hash", transaction.getHash());
    assertEquals("/transactionsByMessage", confirmationRequest.get().getPath());
    assertEquals(
        Arrays.asList("normalized"),
        confirmationRequest.get().getQuery().get("msg_hash"));
  }
}
