package com.ctrip.service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * 服务端直出页面里「把链接发给另一台设备看」时要用的站点根地址。
 *
 * <p>{@code localhost} 的字面意思是「你这台机器」：手机或另一台电脑解不开它，
 * 所以发现是本机回环地址就换成局域网 IPv4（扫码确认页与第三方授权页都靠这一步）。
 * 拿不到局域网地址（例如没联网）才退回请求里的 Host。
 */
final class LocalUrl {

    private LocalUrl() { }

    /** scheme://host:port 形式的站点根地址，末尾不带斜杠 */
    static String root(String scheme, String host, int port) {
        String authority = host == null ? "localhost:" + port : host;
        if (authority.startsWith("localhost") || authority.startsWith("127.")) {
            String lan = siteLocalIpv4();
            if (lan != null) {
                authority = lan + ":" + port;
            }
        }
        return (scheme == null ? "http" : scheme) + "://" + authority;
    }

    /** 挑一个站内可路由的 IPv4 地址；返回 null 表示这台机器只有一个回环口 */
    static String siteLocalIpv4() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignore) {
            // 读网卡失败就退回请求里的 Host，不影响功能——配置项本来就能覆盖
        }
        return null;
    }
}
