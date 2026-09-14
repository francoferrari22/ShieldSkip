package com.shieldskip.nativeapp;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class Blocklist {
    private static final Set<String> EXACT = new HashSet<>(Arrays.asList(
        "doubleclick.net","googlesyndication.com","googleadservices.com","google-analytics.com",
        "adservice.google.com","ads.youtube.com","pagead2.googlesyndication.com","adsrvr.org",
        "adnxs.com","appnexus.com","criteo.com","criteo.net","taboola.com","outbrain.com",
        "unityads.unity3d.com","unityads.com","ironsrc.com","applovin.com","adjust.com",
        "branch.io","kochava.com","vungle.com","chartboost.com","inmobi.com","startapp.com",
        "facebook.net","ads.facebook.com","amazon-adsystem.com","moatads.com","adcolony.com"
    ));
    static boolean isBlocked(String host){
        if(host==null) return false;
        host=host.toLowerCase(Locale.US).trim();
        while(host.endsWith(".")) host=host.substring(0,host.length()-1);
        for(String d: EXACT) if(host.equals(d)||host.endsWith("."+d)) return true;
        return host.contains(".doubleclick.") || host.startsWith("ads.") || host.contains(".ads.") || host.contains("adservice") || host.contains("advertising");
    }
}
