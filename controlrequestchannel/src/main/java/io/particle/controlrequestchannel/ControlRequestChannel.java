package io.particle.controlrequestchannel;

import java.util.Map;

public class ControlRequestChannel {
    private static final int DEFAULT_HANDSHAKE_TIMEOUT = 10000;
    private static final int DEFAULT_REQUEST_TIMEOUT = 60000;
    private static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 1;

    private static final int AES_CCM_NONCE_SIZE = 12;
    private static final int AES_CCM_TAG_SIZE = 8;

    private static final int REQUEST_PACKET_OVERHEAD = AES_CCM_TAG_SIZE + 8;
    private static final int RESPONSE_PACKET_OVERHEAD = AES_CCM_TAG_SIZE + 8;
    private static final int HANDSHAKE_PACKET_OVERHEAD = 2;

    private static final int MAX_REQUEST_PAYLOAD_SIZE = 0xffff;
    private static final int MAX_REQUEST_ID = 0xffff;

    private enum ChannelState {
        NEW,
        OPENING,
        OPEN,
        CLOSED
    }

    private enum HandshakeState {
        ROUND_1,
        ROUND_2,
        CONFIRM
    }

    private ChannelState _channelState = ChannelState.NEW;
    private HandshakeState _handshakeState = HandshakeState.ROUND_1;
    private Stream _stream;
    private String _preSecret;
    private int _maxConcurReqs;
    private int _requestTimeout;
    private int _handshakeTimeout;
    private Map _sentReqs;
    private Map _queuedReqs;
    private byte[] _recvBuf;
    private int _nextReqId;
    byte[] _cliNonce;
    byte[] _servNonce;
    private int _lastCliCtr;
    private int _lastServCtr;
    private boolean _sending;
    private boolean _receiving;

    public ControlRequestChannel(
            Stream stream,
            String secret,
            int concurrentRequests,
            int requestTimeout,
            int handshakeTimeout
    ) throws Exception {
        if (stream == null) {
            throw new Exception("Please specify the input and output stream");
        }
        this._stream = stream;
        if (secret == null || secret.length() == 0) {
            throw new Exception("Secret is empty");
        }
        this._preSecret = secret;
        this._maxConcurReqs = concurrentRequests;
        if (requestTimeout < 0 || handshakeTimeout < 0) {
            throw new Exception("Invalid timeout value");
        }
        this._requestTimeout = requestTimeout;
        this._handshakeTimeout = handshakeTimeout;

        this._lastCliCtr = 0;
        this._lastServCtr = 0;
    }

    public ControlRequestChannel(
            Stream stream,
            String secret
    ) throws Exception {
        this(stream, secret, DEFAULT_MAX_CONCURRENT_REQUESTS, DEFAULT_REQUEST_TIMEOUT, DEFAULT_HANDSHAKE_TIMEOUT);
    }
}
