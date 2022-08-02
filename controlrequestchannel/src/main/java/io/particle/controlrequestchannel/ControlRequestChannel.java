package io.particle.controlrequestchannel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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

    private static final byte[] EC_JPAKE_CLIENT_ID = "client".getBytes();
    private static final byte[] EC_JPAKE_SERVER_ID = "server".getBytes();

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
    private byte[] _secret;

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

        this._callback.onOpen();
    }

    private void _sendHandshake(ByteArrayOutputStream payload) {
        byte[] packet = new byte[payload.size() + HANDSHAKE_PACKET_OVERHEAD];
        this._writeUint16Le(packet, (short) payload.size(), 0);
        System.arraycopy(payload.toByteArray(), 0, packet, 2, payload.size());
        this._stream.write(packet);
    }

    private void _streamData(byte[] chunk) {
        if (this._channelState != ChannelState.OPEN && this._channelState != ChannelState.OPENING) {
            return;
        }
        if (this._receiving) {
            return;
        }
        this._receiving = true;

        try {
            for (;;) {
                if (this._recvBuf.length < 2) {
                    break;
                }
                // TODO: Check if this readUint16Le() replacement works
                ByteBuffer bb = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN);
                short payloadLen = bb.getShort();
                boolean isResp = this._channelState == ChannelState.OPEN;
                int packetLen = payloadLen + (isResp ? RESPONSE_PACKET_OVERHEAD : HANDSHAKE_PACKET_OVERHEAD);
                if (this._recvBuf.length < packetLen) {
                    break;
                }
				byte[] packet = Arrays.copyOf(this._recvBuf, packetLen);
                if (isResp) {
                    this._recvResponse(packet);
                } else {
                    this._recvHandshake(packet);
                }
                this._recvBuf = Arrays.copyOfRange(this._recvBuf, packetLen, this._recvBuf.length - packetLen);
            }
        } finally {
            this._receiving = false;
        }
    }

    private byte[] _genNonce(byte[] fixed, int ctr, boolean isResp) {
		byte[] nonce = new byte[fixed.length + 4];
        if (isResp) {
            ctr = (ctr | 0x80000000) >>> 0;
        }
        this._writeUint32Le(nonce, ctr, 0);
        System.arraycopy(fixed, 0, nonce, 4, fixed.length);
        return nonce;
    }

    private void _recvResponse(byte[] packet) {
        byte[] enc = Arrays.copyOfRange(packet, 2, packet.length);
        byte[] addData = Arrays.copyOfRange(packet, 0, 2);
        byte[] nonce = this._genNonce(this._servNonce, ++this._lastServCtr, true /* isResp */);
        // TODO: Use the initialized AES
    }

    private void _recvHandshake(byte[] packet) {
        switch (this._handshakeState) {
            case ROUND_1:
                byte[] servRound1 = Arrays.copyOfRange(packet, 0, packet.length);

                ByteArrayInputStream servRound1Stream = new ByteArrayInputStream(servRound1);
                try {
                    this._jpake.readRound1(servRound1Stream);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                this._cliHash.update(servRound1);
                this._servHash.update(servRound1);
                this._handshakeState = HandshakeState.ROUND_2;
                break;
            case ROUND_2:
                byte[] servRound2 = Arrays.copyOfRange(packet, 0, packet.length);
                ByteArrayInputStream servRound2Stream = new ByteArrayInputStream(servRound2);
                try {
                    this._jpake.readRound2(servRound2Stream);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                this._cliHash.update(servRound2);
                this._servHash.update(servRound2);

                ByteArrayOutputStream cliRound2 = new ByteArrayOutputStream();
                try {
                    this._jpake.writeRound2(cliRound2);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                this._cliHash.update(cliRound2.toByteArray());
                this._servHash.update(cliRound2.toByteArray());
                this._sendHandshake(cliRound2);

                this._secret = this._jpake.deriveSecret();
                byte[] cliConfirm = this._genConfirm(this._secret, this.EC_JPAKE_CLIENT_ID, this.EC_JPAKE_SERVER_ID, _cliHash.digest());
                this._servHash.update(cliConfirm);
                ByteArrayOutputStream cliConfirmStream = new ByteArrayOutputStream();
                try {
                    cliConfirmStream.write(cliConfirm);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                this._sendHandshake(cliConfirmStream);
                this._handshakeState = HandshakeState.CONFIRM;
                break;
            case CONFIRM:
                break;
        }
    }

    private byte[] _genConfirm(byte[] secret, byte[] localId, byte[] remoteId, byte[] packetsHash) {
        try {
            MessageDigest keyDigestInstance = MessageDigest.getInstance("SHA-256");
            keyDigestInstance.update(secret);
            keyDigestInstance.update("JPAKE_KC".getBytes());
            byte[] key = keyDigestInstance.digest();

            SecretKeySpec secretKeySpec = new SecretKeySpec(key, "SHA-256");
            Mac mac = Mac.getInstance("SHA-256");
            mac.init(secretKeySpec);
            mac.update("KC_1_U".getBytes());
            mac.update(localId);
            mac.update(remoteId);
            mac.update(packetsHash);
            return mac.doFinal();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            e.printStackTrace();
        }
        return new byte[0];
    }

    private void _writeUint16Le(byte[] destination, short value, int offset) {
        byte[] bytes = new byte[4];
        ByteBuffer.wrap(bytes).putShort(value);
        System.arraycopy(bytes, 0, bytes, offset, bytes.length);
    }

    private void _writeUint32Le(byte[] destination, long value, int offset) {
        byte[] bytes = new byte[8];
        ByteBuffer.wrap(bytes).putLong(value);
        System.arraycopy(bytes, 0, bytes, offset, bytes.length);
    }
}
