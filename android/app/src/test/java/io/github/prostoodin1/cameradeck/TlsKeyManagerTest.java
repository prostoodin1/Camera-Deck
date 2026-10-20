package io.github.prostoodin1.cameradeck;

import java.net.Socket;
import java.security.*;
import java.security.cert.X509Certificate;
import javax.net.ssl.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class TlsKeyManagerTest {
    final X509KeyManager platform=new X509ExtendedKeyManager(){
        public String[] getServerAliases(String t,Principal[] i){return t.equals("EC")?new String[]{"v1","v2"}:null;}
        public String chooseServerAlias(String t,Principal[] i,Socket s){return "v1";}
        public String[] getClientAliases(String t,Principal[] i){return null;}
        public String chooseClientAlias(String[] t,Principal[] i,Socket s){return null;}
        public X509Certificate[] getCertificateChain(String a){return new X509Certificate[0];}
        public PrivateKey getPrivateKey(String a){return null;}
    };
    @Test public void retainedOldKeyIsNeverSelected(){
        TlsKeyManager keys=new TlsKeyManager(platform,"v2");
        assertEquals("v2",keys.chooseServerAlias("EC",null,null));
        assertEquals("v2",keys.chooseEngineServerAlias("EC",null,null));
        assertNull(keys.getCertificateChain("v1"));
        assertNull(keys.getPrivateKey("v1"));
    }
    @Test public void platformKeyTypeMatchingIsRespected(){
        assertNull(new TlsKeyManager(platform,"v2").chooseServerAlias("RSA",null,null));
        assertNull(new TlsKeyManager(platform,"missing").chooseServerAlias("EC",null,null));
    }
}
