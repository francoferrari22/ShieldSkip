package com.shieldskip.nativeapp;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ShieldVpnService extends VpnService {
    public static final String ACTION="com.shieldskip.nativeapp.VPN";
    private ParcelFileDescriptor tun; private volatile boolean running; private Thread worker;
    private final ExecutorService dnsExec=Executors.newCachedThreadPool();

    @Override public void onCreate(){ super.onCreate(); createChannel(); }
    private void createChannel(){ if(Build.VERSION.SDK_INT>=26){ NotificationChannel c=new NotificationChannel("shield","ShieldSkip protección",NotificationManager.IMPORTANCE_LOW); getSystemService(NotificationManager.class).createNotificationChannel(c); } }
    private Notification notification(){ Intent i=new Intent(this,MainActivity.class); PendingIntent pi=PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT); Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,"shield"):new Notification.Builder(this); return b.setContentTitle("ShieldSkip activo").setContentText("Filtrando publicidad y rastreadores DNS").setSmallIcon(android.R.drawable.ic_secure).setOngoing(true).setContentIntent(pi).build(); }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(Build.VERSION.SDK_INT>=29) startForeground(10,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE); else startForeground(10,notification());
        if(!running) startVpn(); return START_STICKY;
    }
    private void startVpn(){
        try{
            VpnService.Builder b=new VpnService.Builder().setSession("ShieldSkip").setMtu(1500).addAddress("10.77.0.2",32).addDnsServer("10.77.0.53").addRoute("10.77.0.53",32);
            Set<String> apps=Prefs.apps(this);
            for(String pkg:apps) try{ b.addAllowedApplication(pkg); }catch(Exception ignored){}
            tun=b.establish(); if(tun==null) throw new IllegalStateException("No se pudo establecer VPN");
            running=true; Prefs.active(this,true); Prefs.started(this,System.currentTimeMillis());
            worker=new Thread(this::loop,"ShieldSkip-VPN"); worker.start();
        }catch(Exception e){ Prefs.active(this,false); stopSelf(); }
    }
    private void loop(){
        byte[] buf=new byte[32767];
        while(running && tun!=null){
            try{ FileInputStream in=new FileInputStream(tun.getFileDescriptor()); int n=in.read(buf); if(n<=0) continue; final byte[] packet=Arrays.copyOf(buf,n); DnsPacket.Query q=DnsPacket.parseQuery(packet,n); if(q==null) continue; dnsExec.submit(()->handle(q)); }
            catch(Exception e){ if(running) break; }
        }
    }
    private void handle(DnsPacket.Query q){
        try{
            boolean blocked=Blocklist.isBlocked(q.host); byte[] resp;
            if(blocked){ resp=DnsPacket.response(q,null,true); Prefs.blocked(this,Prefs.blocked(this)+1); Prefs.bytes(this,Prefs.bytes(this)+8000); }
            else{
                DatagramSocket s=new DatagramSocket(); protect(s); s.setSoTimeout(1800); byte[] query=makeDnsQuery(q); DatagramPacket dp=new DatagramPacket(query,query.length,InetAddress.getByName("1.1.1.1"),53); s.send(dp); byte[] rb=new byte[4096]; DatagramPacket rp=new DatagramPacket(rb,rb.length); s.receive(rp); resp=DnsPacket.response(q,Arrays.copyOf(rb,rp.getLength()),false); s.close();
            }
            synchronized(this){ if(tun!=null){ FileOutputStream out=new FileOutputStream(tun.getFileDescriptor()); out.write(resp); out.flush(); } }
        }catch(Exception ignored){}
    }
    private byte[] makeDnsQuery(DnsPacket.Query q){
        byte[] out=new byte[12+q.question.length]; out[0]=(byte)(q.id>>8); out[1]=(byte)q.id; out[2]=1; out[5]=1; System.arraycopy(q.question,0,out,12,q.question.length); return out;
    }
    @Override public void onDestroy(){ running=false; Prefs.active(this,false); try{if(tun!=null)tun.close();}catch(Exception ignored){} tun=null; dnsExec.shutdownNow(); super.onDestroy(); }
    @Override public void onRevoke(){ stopSelf(); }
}
