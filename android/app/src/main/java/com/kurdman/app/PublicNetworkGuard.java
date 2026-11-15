package com.kurdman.app;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Building block for a future isolated web transport; not installed in WebView.
 * A verified public DNS answer is pinned to the actual socket connection. This
 * does not itself establish TLS, proxy WebView traffic, or make a browser safe.
 */
final class PublicNetworkGuard {
    private static final int MAX_DNS_ANSWERS = 32;
    interface Resolver { InetAddress[] resolve(String host) throws IOException; }
    interface SocketFactory { Socket create() throws IOException; }
    private PublicNetworkGuard() { }

    /** Deliberately conservative; reserved or ambiguous ranges are not public. */
    static boolean isPublic(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address && b.length == 4) {
            int a=b[0]&255, c=b[1]&255, d=b[2]&255, last=b[3]&255;
            if (a==0 || a==10 || a==127 || a>=224 || last==0 || last==255) return false;
            if (a==100 && c>=64 && c<=127) return false;
            if (a==169 && c==254) return false;
            if (a==172 && c>=16 && c<=31) return false;
            if (a==192 && c==168) return false;
            if (a==192 && c==0 && (d==0 || d==2)) return false;
            if (a==192 && c==88 && d==99) return false;
            if (a==198 && (c==18 || c==19 || (c==51 && d==100))) return false;
            if (a==203 && c==0 && d==113) return false;
            return true;
        }
        if (!(address instanceof Inet6Address) || b.length != 16) return false;
        int a=b[0]&255, c=b[1]&255, d=b[2]&255, e=b[3]&255;
        // Only global unicast 2000::/3. Exclude IETF special-purpose
        // 2001::/23, documentation, 6to4 and documentation 3fff::/20.
        if ((a & 0xe0) != 0x20) return false;
        if (a==0x20 && c==0x01 && d<=0x01) return false;
        if (a==0x20 && c==0x01 && d==0x0d && e==0xb8) return false;
        if (a==0x20 && c==0x02) return false;
        if (a==0x3f && c==0xff && (d & 0xf0)==0) return false;
        return true;
    }

    /** Reject the entire DNS answer if any member is non-public. */
    static List<InetAddress> resolvePublic(String host, Resolver resolver) throws IOException {
        if (host==null || host.isEmpty() || host.length()>253 || resolver==null)
            throw new IOException("Invalid destination");
        InetAddress[] answers=resolver.resolve(host);
        if (answers==null || answers.length==0 || answers.length>MAX_DNS_ANSWERS)
            throw new IOException("Destination resolution failed");
        List<InetAddress> checked=new ArrayList<>();
        for (InetAddress answer:answers) {
            if (!isPublic(answer)) throw new IOException("Non-public destination refused");
            checked.add(answer);
        }
        return Collections.unmodifiableList(checked);
    }

    /** Production socket must not silently inherit a system SOCKS proxy. */
    static Socket connectDirect(String host, int port, int timeoutMillis) throws IOException {
        return connectPinned(resolvePublic(host, InetAddress::getAllByName), port, timeoutMillis,
                () -> new Socket(java.net.Proxy.NO_PROXY));
    }

    /** Connect to the checked numeric address, never to the hostname again. */
    static Socket connectPinned(List<InetAddress> checked, int port, int timeoutMillis,
                                SocketFactory sockets) throws IOException {
        if (checked==null || checked.isEmpty() || checked.size()>MAX_DNS_ANSWERS
                || port<1 || port>65535 || timeoutMillis<1 || timeoutMillis>30000 || sockets==null)
            throw new IOException("Invalid destination");
        // Do not trust callers to pass the list returned by resolvePublic;
        // snapshot before validating so later list mutations cannot swap it.
        List<InetAddress> snapshot=new ArrayList<>(checked);
        for (InetAddress address:snapshot) if (!isPublic(address))
            throw new IOException("Non-public destination refused");
        InetAddress chosen=snapshot.get(0);
        Socket socket=sockets.create();
        if (socket==null) throw new IOException("Socket unavailable");
        boolean connected=false;
        try {
            socket.connect(new InetSocketAddress(chosen,port),timeoutMillis);
            if (!chosen.equals(socket.getInetAddress()) || socket.getPort()!=port)
                throw new IOException("Connected endpoint differs from checked address");
            connected=true;
            return socket;
        } finally {
            if (!connected) try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
