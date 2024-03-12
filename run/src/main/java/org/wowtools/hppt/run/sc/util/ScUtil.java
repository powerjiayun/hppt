package org.wowtools.hppt.run.sc.util;

import org.wowtools.hppt.common.client.ClientBytesSender;
import org.wowtools.hppt.common.client.ClientSessionLifecycle;
import org.wowtools.hppt.common.client.ClientSessionManager;
import org.wowtools.hppt.common.client.ClientSessionManagerBuilder;
import org.wowtools.hppt.run.sc.pojo.ScConfig;

/**
 * @author liuyu
 * @date 2024/3/12
 */
public class ScUtil {

    //建立ClientSessionManager，并绑上配置的端口
    public static ClientSessionManager createClientSessionManager(ScConfig config, ClientSessionLifecycle lifecycle, ClientBytesSender clientBytesSender) {

        ClientSessionManager clientSessionManager = new ClientSessionManagerBuilder()
                .setBufferSize(config.maxSendBodySize * 2)
                .setLifecycle(lifecycle)
                .setClientBytesSender(clientBytesSender)
                .build();
        for (ScConfig.Forward forward : config.forwards) {
            clientSessionManager.bindPort(forward.localPort);
        }
        return clientSessionManager;
    }
}
