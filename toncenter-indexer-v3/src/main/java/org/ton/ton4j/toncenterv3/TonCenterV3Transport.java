package org.ton.ton4j.toncenterv3;

import java.io.IOException;

@FunctionalInterface
public interface TonCenterV3Transport {

  TonCenterV3TransportResponse execute(TonCenterV3TransportRequest request) throws IOException;
}
