package io.particle.controlrequestchannel;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Random;

import io.particle.ecjpake.EcJpake;

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
    private StreamEventListener _streamEventListener;
    private String _preSecret;
    private ControlRequestChannelCallback _callback;
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
    private EcJpake _jpake;
    private MessageDigest _cliHash;
    private MessageDigest _servHash;

    public ControlRequestChannel(
            Stream stream,
            String secret,
            ControlRequestChannelCallback callback,
            int concurrentRequests,
            int requestTimeout,
            int handshakeTimeout
    ) throws Exception {
        if (stream == null) {
            throw new Exception("Please specify the input and output stream");
        }
        this._stream = stream;
        this._streamEventListener = new StreamEventListener() {
            void onData(byte[] data) {
                // TODO: Implement
            }

            void onClose() {
                // TODO: Implement
            }

            void onError() {
                // TODO: Implement
            }

        };
        this._stream.addListener(this._streamEventListener);
        if (secret == null || secret.length() == 0) {
            throw new Exception("Secret is empty");
        }
        this._preSecret = secret;
        if (callback == null) {
            throw new Exception("Invalid callback");
        }
        this._callback = callback;
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
            String secret,
            ControlRequestChannelCallback callback
    ) throws Exception {
        this(stream, secret, callback, DEFAULT_MAX_CONCURRENT_REQUESTS, DEFAULT_REQUEST_TIMEOUT, DEFAULT_HANDSHAKE_TIMEOUT);
    }

    public void open() throws Exception {
        if (this._channelState != ChannelState.NEW) {
            throw new Exception("Invalid state");
        }
        this._channelState = ChannelState.OPENING;
        this._handshakeState = HandshakeState.ROUND_1;

        // Initialize ECJPAKE with random seed
        Random rand = new Random();
        SecureRandom secRand = new SecureRandom();
        int pwdLen = rand.nextInt(100) + 1;
        byte[] pwd = new byte[pwdLen];
        secRand.nextBytes(pwd);
        this._jpake = new EcJpake(EcJpake.Role.CLIENT, this._preSecret.getBytes(), secRand);

        this._preSecret = null;

        ByteArrayOutputStream cliRound1 = new ByteArrayOutputStream();
        this._jpake.writeRound1(cliRound1);

        this._cliHash = MessageDigest.getInstance("SHA-256");
        this._servHash = MessageDigest.getInstance("SHA-256");
        this._cliHash.update(cliRound1.toByteArray());
        this._servHash.update(cliRound1.toByteArray());

        // TODO: Implement opening
        this._callback.onOpen();
    }

    private void _sendHandshake(ByteArrayOutputStream payload) {
        byte[] packet = new byte[payload.size() + HANDSHAKE_PACKET_OVERHEAD];
        System.arraycopy(payload.toByteArray(), 0, packet, 0, payload.size());
        // I'm, not sure what this double write to the packet does:
        // util.writeUint16Le(packet, payload.length, 0);
        // packet.set(payload, 2);
        this._stream.write(packet);
    }
}
