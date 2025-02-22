/*
 * This file is part of bytesocks, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.bytesocks.ws;

import me.lucko.bytesocks.BytesocksServer;
import me.lucko.bytesocks.util.RateLimiter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.jooby.WebSocket;
import io.jooby.WebSocketCloseStatus;
import io.jooby.WebSocketMessage;
import io.jooby.internal.WebSocketMessageImpl;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

public class Channel implements WebSocket.OnConnect, WebSocket.OnMessage, WebSocket.OnClose {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(Channel.class);

    private final ChannelRegistry registry;

    /** The channel id */
    private final String id;
    /** A collection of connected sockets */
    private final Set<WebSocket> sockets = ConcurrentHashMap.newKeySet();
    /** The rate limiter */
    private final RateLimiter rateLimiter;

    public Channel(ChannelRegistry registry, String id, RateLimiter rateLimiter) {
        this.registry = registry;
        this.id = id;
        this.rateLimiter = rateLimiter;
    }

    public String getId() {
        return this.id;
    }

    public int getConnectedCount() {
        return this.sockets.size();
    }

    public void gracefullyClose() {
        for (WebSocket socket : this.sockets) {
            socket.close(WebSocketCloseStatus.SERVICE_RESTARTED);
        }
    }

    public void close() {
        this.registry.channelClosed(this);
    }

    @Override
    public void onConnect(@Nonnull WebSocket ws) {
        this.sockets.add(ws);

        LOGGER.info("[CHANNEL: connected]\n" +
                "    channel id = " + this.id + "\n" +
                "    new connected count = " + this.sockets.size() + "\n" +
                BytesocksServer.describeForLogger(ws.getContext())
        );
    }

    @Override
    public void onClose(@Nonnull WebSocket ws, @Nonnull WebSocketCloseStatus status) {
        this.sockets.remove(ws);

        LOGGER.info("[CHANNEL: disconnected]\n" +
                "    channel id = " + this.id + "\n" +
                "    new connected count = " + this.sockets.size() + "\n" +
                "    status = " + status + "\n" +
                BytesocksServer.describeForLogger(ws.getContext())
        );

        if (this.sockets.isEmpty()) {
            close();
        }
    }

    @Override
    public void onMessage(@Nonnull WebSocket ws, @Nonnull WebSocketMessage message) {
        String ipAddress = BytesocksServer.getIpAddress(ws.getContext());

        // check rate limit
        if (this.rateLimiter.check(ipAddress)) {
            ws.close(WebSocketCloseStatus.POLICY_VIOLATION);
            return;
        }

        byte[] msg = ((WebSocketMessageImpl) message).bytes();

        // forward message
        for (WebSocket socket : this.sockets) {
            if (socket.equals(ws)) {
                continue;
            }

            socket.send(msg);
        }
    }

}

