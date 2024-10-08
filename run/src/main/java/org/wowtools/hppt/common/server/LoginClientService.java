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

    public static final class Config {
        /**
         * 每行一条用户名和密码
         */
        public ArrayList<String[]> users = new ArrayList<>();

        /**
         * 密码重试次数
         */
        public int passwordRetryNum;
    }


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


    private static final class ClientInfo {
        private final String user;
        private final String password;

        public ClientInfo(String user, String password) {
            this.user = user;
            this.password = password;
        }

        private int passwordErrorNum;
    }

    private final Map<String, ClientInfo> users;
    private final int passwordRetryNum;

    public LoginClientService(Config config) {
        Map<String, ClientInfo> _users = new HashMap<>();
        for (String[] u : config.users) {
            _users.put(u[0], new ClientInfo(u[0], u[1]));
        }
        users = Map.copyOf(_users);
        passwordRetryNum = config.passwordRetryNum;
    }

    /**
     * 传入的code能解密出客户端id，则登录成功
     *
     * @param code                loginCode
     * @param clientActiveWatcher 用以观察客户端活跃状态的变化
     * @return 登录成功则返回Client对象，否则抛出异常
     */
    public Client login(String code, ClientActiveWatcher clientActiveWatcher) {
        String[] strs = code.split(" ", 2);
        String user = strs[0];
        String pwdCode = strs[1];
        ClientInfo clientInfo = users.get(user);
        if (null == clientInfo) {
            throw new RuntimeException("用户名不存在");
        }
        if (clientInfo.passwordErrorNum > passwordRetryNum) {
            throw new RuntimeException("多次登录失败，用户已锁定");
        }
        AesCipherUtil aesCipherUtil = new AesCipherUtil(clientInfo.password, System.currentTimeMillis());
        try {
            if (new String(aesCipherUtil.descriptor.decrypt(BytesUtil.base642bytes(pwdCode)), StandardCharsets.UTF_8).equals(clientInfo.password)) {
                clientInfo.passwordErrorNum = 0;
                return new Client(user, aesCipherUtil, clientActiveWatcher);
            }
        } catch (Exception ignored) {
        }
        clientInfo.passwordErrorNum++;//理论上会有并发导致次数不准，可忽略
        throw new RuntimeException("密码不正确或对时时差过长");
    }

}
