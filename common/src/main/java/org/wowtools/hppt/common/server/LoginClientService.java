package org.wowtools.hppt.common.server;

import org.wowtools.hppt.common.pojo.SessionBytes;
import org.wowtools.hppt.common.util.AesCipherUtil;
import org.wowtools.hppt.common.util.BytesUtil;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * @author liuyu
 * @date 2023/12/18
 */
public class LoginClientService {


    public static final class Client {
        public final String clientId;
        public final AesCipherUtil aesCipherUtil;

        private final BlockingQueue<String> commandQueue = new LinkedBlockingQueue<>();

        private final BlockingQueue<SessionBytes> sessionBytesQueue = new LinkedBlockingQueue<>();
        public final BlockingQueue<byte[]> receiveClientBytes = new LinkedBlockingQueue<>();

        private final HashMap<Integer, ServerSession> sessions = new HashMap<>();

        private final ClientActiveWatcher activeWatcher;


        private Client(String clientId, AesCipherUtil aesCipherUtil, ClientActiveWatcher activeWatcher) {
            this.clientId = clientId;
            this.aesCipherUtil = aesCipherUtil;
            this.activeWatcher = activeWatcher;
        }

        //添加一条向客户端发送的命令
        public void addCommand(String cmd) {
            commandQueue.add(cmd);
        }

        //取出所有需要向客户端发送的命令 无命令则返回null
        public List<String> fetchCommands() {
            if (commandQueue.isEmpty()) {
                return null;
            }
            List<String> res = new LinkedList<>();
            commandQueue.drainTo(res);
            return res;
        }

        public void addSession(ServerSession session) {
            synchronized (sessions) {
                int s1 = sessions.size();
                sessions.put(session.getSessionId(), session);
                if (s1 == 0) {
                    activeWatcher.toActivity();
                }
            }
        }

        public void removeSession(ServerSession session) {
            synchronized (sessions) {
                if (sessions.remove(session.getSessionId()) != null && sessions.isEmpty()) {
                    activeWatcher.toInactivity();
                }
            }
        }

        //添加一条向客户端发送的bytes
        public void addBytes(int sessionId, byte[] bytes) {
            sessionBytesQueue.add(new SessionBytes(sessionId, bytes));
        }

        //取出所有需要向客户端发送的bytes 取出的bytes会按相同sessionId进行整合 无bytes则返回null
        public List<SessionBytes> fetchBytes(long maxReturnBodySize) {
            if (sessionBytesQueue.isEmpty()) {
                return null;
            }
            List<SessionBytes> bytesList = new LinkedList<>();
            if (maxReturnBodySize < 0) {
                sessionBytesQueue.drainTo(bytesList);
            } else {
                //根据maxReturnBodySize的限制取出队列中的数据返回
                long currentReturnBodySize = 0L;
                while (currentReturnBodySize < maxReturnBodySize) {
                    SessionBytes next = sessionBytesQueue.poll();
                    if (null == next) {
                        break;
                    }
                    bytesList.add(next);
                    currentReturnBodySize += next.getBytes().length;
                }
            }
            return merge(bytesList);

        }

        //取出所有需要向客户端发送的bytes 取出的bytes会按相同sessionId进行整合 无bytes则阻塞3秒后返回
        public List<SessionBytes> fetchBytesBlocked(long maxReturnBodySize) {
            List<SessionBytes> bytesList = new LinkedList<>();
            SessionBytes first;
            try {
                first = sessionBytesQueue.poll(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return bytesList;
            }
            if (null == first) {
                return bytesList;
            }
            bytesList.add(first);
            if (sessionBytesQueue.isEmpty()) {
                return bytesList;
            }
            if (maxReturnBodySize < 0) {
                sessionBytesQueue.drainTo(bytesList);
                return merge(bytesList);
            } else {
                //根据maxReturnBodySize的限制取出队列中的数据返回
                long currentReturnBodySize = first.getBytes().length;
                while (currentReturnBodySize < maxReturnBodySize) {
                    SessionBytes next = sessionBytesQueue.poll();
                    if (null == next) {
                        break;
                    }
                    bytesList.add(next);
                    currentReturnBodySize += next.getBytes().length;
                }
                return merge(bytesList);
            }

        }

        private static List<SessionBytes> merge(List<SessionBytes> bytesList) {
            Map<Integer, List<byte[]>> bytesMap = new HashMap<>();
            for (SessionBytes bytes : bytesList) {
                bytesMap.computeIfAbsent(bytes.getSessionId(), (r) -> new LinkedList<>())
                        .add(bytes.getBytes());
            }
            List<SessionBytes> res = new ArrayList<>(bytesMap.size());
            bytesMap.forEach((sessionId, bytes) -> {
                res.add(new SessionBytes(sessionId, BytesUtil.merge(bytes)));
            });
            return res;
        }
    }

    /**
     * 客户端是否活跃的观察器
     */
    public interface ClientActiveWatcher {
        /**
         * 客户端变得不活跃时触发
         */
        void toInactivity();

        /**
         * 客户端变得活跃时触发
         */
        void toActivity();
    }


    private final String[] allowClientIds;

    public LoginClientService(String[] allowClientIds) {
        this.allowClientIds = allowClientIds;
    }

    /**
     * @param allowClientIds 允许的clientId
     */
    public LoginClientService(Collection<String> allowClientIds) {
        this.allowClientIds = new String[allowClientIds.size()];
        allowClientIds.toArray(this.allowClientIds);
    }

    /**
     * 传入的code能解密出客户端id，则登录成功
     *
     * @param code                loginCode
     * @param clientActiveWatcher 用以观察客户端活跃状态的变化
     * @return 登录成功则返回Client对象，否则返回null
     */
    public Client login(String code, ClientActiveWatcher clientActiveWatcher) {
        byte[] bytesCode = BytesUtil.base642bytes(code);
        for (String clientId : allowClientIds) {
            AesCipherUtil aesCipherUtil = new AesCipherUtil(clientId, System.currentTimeMillis());
            try {
                if (new String(aesCipherUtil.descriptor.decrypt(bytesCode), StandardCharsets.UTF_8).equals(clientId)) {
                    return new Client(clientId, aesCipherUtil, clientActiveWatcher);
                }
            } catch (Exception e) {
            }
        }
        return null;
    }

}
