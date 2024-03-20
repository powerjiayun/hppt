package org.wowtools.hppt.run.sc.common;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.internal.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.wowtools.hppt.common.client.*;
import org.wowtools.hppt.common.pojo.SessionBytes;
import org.wowtools.hppt.common.util.AesCipherUtil;
import org.wowtools.hppt.common.util.BytesUtil;
import org.wowtools.hppt.common.util.Constant;
import org.wowtools.hppt.run.sc.pojo.ScConfig;
import org.wowtools.hppt.run.sc.util.ScUtil;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author liuyu
 * @date 2024/3/12
 */
@Slf4j
public abstract class ClientSessionService {
    protected final ScConfig config;
    private final ClientSessionManager clientSessionManager;

    private final BlockingQueue<String> sendCommandQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<SessionBytes> sendBytesQueue = new LinkedBlockingQueue<>();

    private final Map<Integer, ClientBytesSender.SessionIdCallBack> sessionIdCallBackMap = new HashMap<>();//<newSessionFlag,cb>

    private AesCipherUtil aesCipherUtil;

    private Long dt;

    private boolean firstLoginErr = true;
    private boolean noLogin = true;

    private volatile boolean actived = true;

    /**
     * 当一个事件结束时发起的回调
     */
    @FunctionalInterface
    protected interface Cb {
        void end();
    }

    public ClientSessionService(ScConfig config) throws Exception {
        this.config = config;
        connectToServer(config, () -> {
            log.info("连接建立完成");
            Thread.startVirtualThread(() -> {
                //建立连接后，获取时间戳
                sendBytesToServer("dt".getBytes(StandardCharsets.UTF_8));
                //等待时间戳返回
                while (null == dt) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                //登录
                sendLoginCommand();
            });
        });
        clientSessionManager = ScUtil.createClientSessionManager(config,
                buildClientSessionLifecycle(), buildClientBytesSender());
        buildSendThread().start();
    }

    /**
     * 与服务端建立连接
     *
     * @param config 配置文件
     * @param cb     请在连接完成后主动调用cb.end()
     */
    protected abstract void connectToServer(ScConfig config, Cb cb) throws Exception;

    /**
     * 发送字节到服务端的具体方法
     *
     * @param bytes bytes
     */
    protected abstract void sendBytesToServer(byte[] bytes);

    /**
     * 收到服务端传过来的字节时，主动调用此方法进行接收操作
     *
     * @param bytes
     * @throws Exception
     */
    protected void receiveServerBytes(byte[] bytes) throws Exception {
        if (noLogin) {
            String s = new String(bytes, StandardCharsets.UTF_8);
            String[] cmd = s.split(" ", 2);
            switch (cmd[0]) {
                case "dt":
                    long localTs = System.currentTimeMillis();
                    long serverTs = Long.parseLong(cmd[1]);
                    dt = serverTs - localTs;
                    break;
                case "login":
                    String state = cmd[1];
                    if ("1".equals(state)) {
                        noLogin = false;
                        log.info("登录成功");
                    } else if (firstLoginErr) {
                        firstLoginErr = false;
                        log.warn("第一次登录失败 重试");
                        Thread.sleep(10000);
                        sendLoginCommand();
                    } else {
                        log.error("登录失败");
                        System.exit(0);
                    }
                    break;
                default:
                    log.warn("未知命令 {}", s);
            }
        } else {
            ClientTalker.receiveServerBytes(config, bytes, clientSessionManager, aesCipherUtil, sendCommandQueue, sessionIdCallBackMap);
        }
    }

    /**
     * 当发生难以修复的异常等情况时，主动调用此方法结束当前服务，以便后续自动重启等操作
     */
    protected void exit() {
        actived = false;
        clientSessionManager.close();
    }

    private Thread buildSendThread() {
        return new Thread(() -> {
            while (actived) {
                try {
                    byte[] sendBytes = ClientTalker.buildSendToServerBytes(config, config.maxSendBodySize, sendCommandQueue, sendBytesQueue, aesCipherUtil, true);
                    if (null != sendBytes) {
                        log.debug("sendBytes {}", sendBytes.length);
                        sendBytesToServer(sendBytes);
                    }
                } catch (Exception e) {
                    log.warn("发送消息异常", e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                        break;
                    }
                }
            }

        });
    }


    private void sendLoginCommand() {
        aesCipherUtil = new AesCipherUtil(config.clientId, System.currentTimeMillis() + dt);
        String loginCode = BytesUtil.bytes2base64(aesCipherUtil.encryptor.encrypt(config.clientId.getBytes(StandardCharsets.UTF_8)));
        sendBytesToServer(("login " + loginCode).getBytes(StandardCharsets.UTF_8));
    }


    private ClientSessionLifecycle buildClientSessionLifecycle() {
        ClientSessionLifecycle common = new ClientSessionLifecycle() {
            @Override
            public void closed(ClientSession clientSession) {
                sendCommandQueue.add(String.valueOf(Constant.SsCommands.CloseSession) + clientSession.getSessionId());
            }
        };
        if (StringUtil.isNullOrEmpty(config.lifecycle)) {
            return common;
        } else {
            try {
                Class<? extends ClientSessionLifecycle> clazz = (Class<? extends ClientSessionLifecycle>) Class.forName(config.lifecycle);
                ClientSessionLifecycle custom = clazz.getDeclaredConstructor().newInstance();
                return new ClientSessionLifecycle() {
                    @Override
                    public void closed(ClientSession clientSession) {
                        common.closed(clientSession);
                        custom.closed(clientSession);
                    }
                };
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private ClientBytesSender buildClientBytesSender() {
        return new ClientBytesSender() {
            private final AtomicInteger newSessionFlagIdx = new AtomicInteger();

            @Override
            public void connected(int port, ChannelHandlerContext ctx, SessionIdCallBack cb) {
                for (ScConfig.Forward forward : config.forwards) {
                    if (forward.localPort == port) {
                        int newSessionFlag = newSessionFlagIdx.addAndGet(1);
                        String cmd = Constant.SsCommands.CreateSession + forward.remoteHost + Constant.sessionIdJoinFlag + forward.remotePort + Constant.sessionIdJoinFlag + newSessionFlag;
                        sendCommandQueue.add(cmd);
                        log.debug("connected command: {}", cmd);
                        sessionIdCallBackMap.put(newSessionFlag, cb);
                        return;
                    }
                }
                throw new RuntimeException("未知 localPort " + port);
            }

            @Override
            public void sendToTarget(ClientSession clientSession, byte[] bytes) {
                sendBytesQueue.add(new SessionBytes(clientSession.getSessionId(), bytes));
            }
        };
    }
}
