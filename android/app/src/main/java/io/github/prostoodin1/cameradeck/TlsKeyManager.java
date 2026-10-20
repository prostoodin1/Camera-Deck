package io.github.prostoodin1.cameradeck;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import javax.net.ssl.*;

/** Keep platform key-type/issuer matching, but never select the retained legacy key. */
final class TlsKeyManager extends X509ExtendedKeyManager {
    private final X509KeyManager platform;
    private final String alias;
    TlsKeyManager(X509KeyManager platform,String alias){this.platform=platform;this.alias=alias;}
    @Override public String[] getServerAliases(String type,Principal[] issuers){
        String[] matches=platform.getServerAliases(type,issuers);
        if(matches!=null)for(String match:matches)if(alias.equals(match))return new String[]{alias};
        return null;
    }
    @Override public String chooseServerAlias(String type,Principal[] issuers,Socket socket){
        return getServerAliases(type,issuers)==null?null:alias;
    }
    @Override public String chooseEngineServerAlias(String type,Principal[] issuers,SSLEngine engine){
        return chooseServerAlias(type,issuers,null);
    }
    @Override public X509Certificate[] getCertificateChain(String name){return alias.equals(name)?platform.getCertificateChain(name):null;}
    @Override public PrivateKey getPrivateKey(String name){return alias.equals(name)?platform.getPrivateKey(name):null;}
    @Override public String[] getClientAliases(String type,Principal[] issuers){return null;}
    @Override public String chooseClientAlias(String[] types,Principal[] issuers,Socket socket){return null;}
    @Override public String chooseEngineClientAlias(String[] types,Principal[] issuers,SSLEngine engine){return null;}
}
