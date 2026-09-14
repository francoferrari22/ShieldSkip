package com.shieldskip.nativeapp;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class DnsPacket {
    static final class Query { int id; int srcPort; byte[] srcIp; byte[] dstIp; byte[] question; String host; }
    static Query parseQuery(byte[] p, int len){
        if(len<28) return null;
        int version=(p[0]>>4)&15; int ihl=(p[0]&15)*4;
        if(version!=4 || ihl<20 || len<ihl+8) return null;
        if((p[9]&255)!=17) return null;
        int udp=ihl; int srcPort=u16(p,udp); int dstPort=u16(p,udp+2); int dns=udp+8;
        if(dstPort!=53 || len<dns+12) return null;
        Query q=new Query(); q.id=u16(p,dns); q.srcPort=srcPort; q.srcIp=new byte[]{p[12],p[13],p[14],p[15]}; q.dstIp=new byte[]{p[16],p[17],p[18],p[19]};
        int pos=dns+12; int start=pos; StringBuilder host=new StringBuilder();
        while(pos<len){ int n=p[pos++]&255; if(n==0) break; if((n&0xc0)!=0) return null; if(pos+n>len) return null; if(host.length()>0) host.append('.'); host.append(new String(p,pos,n,java.nio.charset.StandardCharsets.US_ASCII)); pos+=n; }
        if(pos>=len) return null; pos++; // QTYPE
        if(pos+4>len) return null; pos+=4;
        q.host=host.toString(); q.question=java.util.Arrays.copyOfRange(p,start,pos); return q;
    }
    static byte[] response(Query q, byte[] dnsPayload, boolean blocked){
        byte[] body;
        if(blocked){
            ByteArrayOutputStream b=new ByteArrayOutputStream();
            write16(b,q.id); write16(b,0x8183); write16(b,0); write16(b,0); write16(b,0); write16(b,0); b.write(q.question,0,q.question.length); body=b.toByteArray();
        } else body=dnsPayload;
        int total=20+8+body.length; byte[] out=new byte[total];
        out[0]=0x45; out[1]=0; put16(out,2,total); put16(out,4,0); put16(out,6,0); out[8]=64; out[9]=17;
        System.arraycopy(q.dstIp,0,out,12,4); System.arraycopy(q.srcIp,0,out,16,4);
        int udp=20; put16(out,udp,53); put16(out,udp+2,q.srcPort); put16(out,udp+4,8+body.length); put16(out,udp+6,0);
        System.arraycopy(body,0,out,28,body.length);
        put16(out,10,checksum(out,0,20)); put16(out,udp+6,udpChecksum(out,udp,8+body.length)); return out;
    }
    private static int checksum(byte[] b,int off,int len){ long s=0; for(int i=off;i<off+len;i+=2){s+=((b[i]&255)<<8)|((i+1<off+len)?(b[i+1]&255):0); while((s>>>16)!=0)s=(s&0xffff)+(s>>>16);} return (int)(~s)&0xffff; }
    private static int udpChecksum(byte[] b,int off,int len){ long s=0; s+=u8(b,12)<<8|u8(b,13); s+=u8(b,14)<<8|u8(b,15); s+=u8(b,16)<<8|u8(b,17); s+=u8(b,18)<<8|u8(b,19); s+=17; s+=len; for(int i=off;i<off+len;i+=2){s+=((b[i]&255)<<8)|((i+1<off+len)?(b[i+1]&255):0); while((s>>>16)!=0)s=(s&0xffff)+(s>>>16);} return (int)(~s)&0xffff; }
    private static int u16(byte[] b,int i){return ((b[i]&255)<<8)|(b[i+1]&255);} private static int u8(byte[]b,int i){return b[i]&255;} private static void put16(byte[]b,int i,int v){b[i]=(byte)(v>>8);b[i+1]=(byte)v;} private static void write16(ByteArrayOutputStream b,int v){b.write((v>>8)&255);b.write(v&255);}
}
