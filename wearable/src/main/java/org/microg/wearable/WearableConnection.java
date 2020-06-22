/*
 * SPDX-FileCopyrightText: 2015, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.wearable;

import com.squareup.wire.Wire;

import org.microg.wearable.proto.Connect;
import org.microg.wearable.proto.MessagePiece;
import org.microg.wearable.proto.RootMessage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import okio.ByteString;

public abstract class WearableConnection implements Runnable {
    private static String B64ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    protected static Wire wire = new Wire();

    private HashMap<Integer, List<MessagePiece>> piecesQueues = new HashMap<Integer, List<MessagePiece>>();
    private final Listener listener;

    public WearableConnection(Listener listener) {
        this.listener = listener;
    }

    public static String base64encode(byte[] bytes) {
        int paddingCount = (3 - (bytes.length % 3)) % 3;
        byte[] padded = new byte[bytes.length + paddingCount];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i += 3) {
            int j = ((padded[i] & 0xff) << 16) + ((padded[i + 1] & 0xff) << 8) + (padded[i + 2] & 0xff);
            sb.append(B64ALPHABET.charAt((j >> 18) & 0x3f)).append(B64ALPHABET.charAt((j >> 12) & 0x3f))
                    .append(B64ALPHABET.charAt((j >> 6) & 0x3f)).append(B64ALPHABET.charAt(j & 0x3f));
        }
        return sb.substring(0, sb.length() - paddingCount);
    }

    public static String calculateDigest(byte[] bytes) {
        try {
            return base64encode(MessageDigest.getInstance("SHA1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA1 not supported => platform not supported");
        }
    }

    public void writeMessage(RootMessage message) throws IOException {
        byte[] bytes = message.toByteArray();
        // TODO: cut in pieces
        writeMessagePiece(new MessagePiece.Builder()
                .data(ByteString.of(bytes))
                .digest(calculateDigest(bytes))
                .thisPiece(1)
                .totalPieces(1).build());
    }

    protected abstract void writeMessagePiece(MessagePiece piece) throws IOException;

    protected RootMessage readMessage() throws IOException {
        while (true) {
            System.out.println("Waiting for new message...");
            MessagePiece piece = readMessagePiece();
            if (piece.totalPieces == 1) {
                return wire.parseFrom(piece.data.toByteArray(), RootMessage.class);
            } else {
                if (piece.thisPiece == 1) {
                    List<MessagePiece> queue = piecesQueues.get(piece.queueId);
                    String oldDigest = null;
                    if (queue != null) {
                        oldDigest = queue.get(0).digest;
                    }
                    queue = new ArrayList<MessagePiece>(piece.totalPieces);
                    queue.add(piece);
                    piecesQueues.put(piece.queueId, queue);
                    if (oldDigest != null) {
                        throw new IOException("Could not finish message of digest " + oldDigest + ", queue is used for newer messagee");
                    }
                } else {
                    List<MessagePiece> queue = piecesQueues.get(piece.queueId);
                    if (queue == null || !queue.get(0).digest.equals(piece.digest)) {
                        throw new IOException("Received " + piece.thisPiece + " before first piece.");
                    }
                    if (queue.size() + 1 != piece.thisPiece) {
                        throw new IOException("Received " + piece.thisPiece + " but expected piece" + queue.size() + 1);
                    }
                    queue.add(piece);
                    if (piece.thisPiece.equals(piece.totalPieces)) {
                        piecesQueues.remove(piece.queueId);
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        for (MessagePiece messagePiece : queue) {
                            messagePiece.data.write(bos);
                        }
                        byte[] bytes = bos.toByteArray();
                        if (!calculateDigest(bytes).equals(piece.digest)) {
                            throw new IOException("Merged pieces have digest " + calculateDigest(bytes) + ", but should be " + piece.digest);
                        }
                        return wire.parseFrom(bytes, RootMessage.class);
                    }
                }
            }
        }
    }

    protected abstract MessagePiece readMessagePiece() throws IOException;

    public abstract void close() throws IOException;

    @Override
    public void run() {
        try {
            listener.onConnected(this);
            RootMessage message;
            while ((message = readMessage()) != null) {
                listener.onMessage(this, message);
            }
        } catch (IOException e) {
            // quit
        }
        System.out.println("WearableConnection closed");
        listener.onDisconnected();
    }

    public interface Listener {
        void onConnected(WearableConnection connection);
        void onMessage(WearableConnection connection, RootMessage message);
        void onDisconnected();
    }
}
