package com.kurdman.app;

import static org.junit.Assert.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Address/pinning contracts only; not WebView, TLS or real-device acceptance. */
public class PublicNetworkGuardTest {
    private static InetAddress ip(String literal) throws Exception {return InetAddress.getByName(literal);}
    @Test public void nonPublicAndSpecialPurposeAddressesAreRefused() throws Exception {
        String[] bad={"0.0.0.0","10.2.3.4","100.64.0.1","100.127.255.254","127.0.0.1",
                "169.254.1.1","172.16.0.1","172.31.9.9","192.168.1.1","192.0.0.9",
                "192.0.2.3","192.88.99.1","198.18.0.1","198.19.1.1","198.51.100.1",
                "203.0.113.1","224.0.0.1","240.1.2.3","255.255.255.255",
                "::","::1","fc00::1","fe80::1","ff02::1","::ffff:127.0.0.1",
                "64:ff9b::a00:1","2001:db8::1","2001:2::1","2002:0808:0808::1","3fff::1"};
        for(String value:bad)assertFalse("Must reject "+value,PublicNetworkGuard.isPublic(ip(value)));
    }
    @Test public void ordinaryPublicAddressesAreAcceptedWithoutAllowingNeighboursOfPrivateRanges() throws Exception {
        for(String value:new String[]{"8.8.8.8","1.1.1.1","100.63.0.1","100.128.0.1",
                "172.15.0.1","172.32.0.1","2001:4860:4860::8888","2606:4700:4700::1111"})
            assertTrue("Expected public "+value,PublicNetworkGuard.isPublic(ip(value)));
        assertFalse(PublicNetworkGuard.isPublic(null));
    }
    @Test public void mixedDnsAnswerAndEmptyOrOverlargeAnswersFailClosed() throws Exception {
        for(InetAddress[] answer:new InetAddress[][]{{ip("8.8.8.8"),ip("127.0.0.1")},{},new InetAddress[33]}) {
            try {PublicNetworkGuard.resolvePublic("example.test",host->answer);fail();} catch(IOException expected) { }
        }
        try {PublicNetworkGuard.resolvePublic("example.test",host->{throw new IOException("dns unavailable");});fail();}catch(IOException expected){}
    }
    static final class FakeSocket extends Socket {
        InetAddress actual; int port; int timeout; boolean closed; boolean wrong;
        @Override public void connect(SocketAddress target,int timeout) throws IOException {
            InetSocketAddress endpoint=(InetSocketAddress)target;
            if(endpoint.isUnresolved())throw new IOException("hostname must not be resolved again");
            this.actual=wrong?InetAddress.getByName("127.0.0.1"):endpoint.getAddress();
            this.port=endpoint.getPort();this.timeout=timeout;
        }
        @Override public InetAddress getInetAddress(){return actual;}
        @Override public int getPort(){return port;}
        @Override public synchronized void close(){closed=true;}
    }
    @Test public void dnsRebindingCannotCauseASecondResolutionDuringConnect() throws Exception {
        AtomicInteger lookups=new AtomicInteger();InetAddress first=ip("8.8.8.8");FakeSocket socket=new FakeSocket();
        List<InetAddress> answers=PublicNetworkGuard.resolvePublic("example.test",host->{
            try {return new InetAddress[]{lookups.getAndIncrement()==0?first:ip("127.0.0.1")};}
            catch(Exception e){throw new IOException(e);}
        });
        assertSame(socket,PublicNetworkGuard.connectPinned(answers,443,5000,()->socket));
        assertEquals(1,lookups.get());assertEquals(first,socket.actual);assertEquals(443,socket.port);assertEquals(5000,socket.timeout);
    }
    @Test public void cannotSmugglePrivateAnswerOrMismatchedConnectedEndpoint() throws Exception {
        FakeSocket socket=new FakeSocket();
        try {PublicNetworkGuard.connectPinned(Arrays.asList(ip("8.8.8.8"),ip("10.0.0.1")),443,1000,()->socket);fail();}catch(IOException expected){}
        assertNull(socket.actual);
        socket.wrong=true;
        try {PublicNetworkGuard.connectPinned(Collections.singletonList(ip("8.8.8.8")),443,1000,()->socket);fail();}catch(IOException expected){}
        assertTrue(socket.closed);
    }
    @Test public void invalidPortsAndTimeoutsDoNotCreateASocket() throws Exception {
        AtomicInteger sockets=new AtomicInteger();
        for(int[] args:new int[][]{{0,1000},{65536,1000},{443,0},{443,30001}}){
            try{PublicNetworkGuard.connectPinned(Collections.singletonList(ip("8.8.8.8")),args[0],args[1],()->{sockets.incrementAndGet();return new FakeSocket();});fail();}catch(IOException expected){}
        }
        assertEquals(0,sockets.get());
    }
}
