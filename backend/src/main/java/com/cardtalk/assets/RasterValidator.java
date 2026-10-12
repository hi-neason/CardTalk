package com.cardtalk.assets;

import com.cardtalk.api.ApiException;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.CRC32;

final class RasterValidator {
    record Dimensions(int width,int height) {}
    private static ApiException invalid() { return new ApiException(400,"INVALID_IMAGE","图片内容或格式无效，请使用完整的 PNG、JPEG 或 WebP 图片"); }
    static Dimensions validate(byte[] bytes,String mediaType,int maxBytes,int maxDimension,long maxPixels) {
        if(mediaType==null||bytes==null||bytes.length==0) throw invalid();if(bytes.length>maxBytes) throw new ApiException(413,"ASSET_TOO_LARGE","图片超过当前上传容量");
        try {
            Dimensions dimensions;
            if(mediaType.equals("image/png")) { png(bytes);dimensions=decode(bytes,"png",maxDimension,maxPixels); }
            else if(mediaType.equals("image/jpeg")) { jpeg(bytes);dimensions=decode(bytes,"jpeg",maxDimension,maxPixels); }
            else if(mediaType.equals("image/webp")) dimensions=webp(bytes);
            else throw invalid();
            check(dimensions,maxDimension,maxPixels);return dimensions;
        } catch(IOException|IllegalArgumentException|IndexOutOfBoundsException failure) { throw invalid(); }
    }
    private static void check(Dimensions d,int maximum,long pixels) { if(d.width()<1||d.height()<1||d.width()>maximum||d.height()>maximum||(long)d.width()*d.height()>pixels) throw new ApiException(413,"ASSET_DIMENSIONS_EXCEEDED","图片尺寸或像素总量过大，请缩小后上传"); }
    private static Dimensions decode(byte[] bytes,String format,int maxDimension,long maxPixels) throws IOException {
        var readers=ImageIO.getImageReadersByFormatName(format);if(!readers.hasNext()) throw invalid();ImageReader reader=readers.next();
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(input,true,true);var dimensions=new Dimensions(reader.getWidth(0),reader.getHeight(0));check(dimensions,maxDimension,maxPixels);
            reader.addIIOReadWarningListener((source,warning)->{throw invalid();});
            var decoded=reader.read(0);if(decoded==null||decoded.getWidth()!=dimensions.width()||decoded.getHeight()!=dimensions.height()) throw invalid();decoded.flush();return dimensions;
        } finally { reader.dispose(); }
    }
    private static void png(byte[] bytes) {
        byte[] signature={(byte)137,80,78,71,13,10,26,10};if(bytes.length<33||!Arrays.equals(Arrays.copyOf(bytes,8),signature)) throw invalid();
        var input=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);int cursor=8;boolean first=true;
        while(cursor+12<=bytes.length) {
            int length=input.getInt(cursor);if(length<0||length>bytes.length-cursor-12) throw invalid();String type=new String(bytes,cursor+4,4,java.nio.charset.StandardCharsets.US_ASCII);
            if(first&&(!type.equals("IHDR")||length!=13)) throw invalid();first=false;
            CRC32 crc=new CRC32();crc.update(bytes,cursor+4,length+4);if(crc.getValue()!=Integer.toUnsignedLong(input.getInt(cursor+8+length))) throw invalid();
            cursor+=12+length;if(type.equals("IEND")) { if(length!=0||cursor!=bytes.length) throw invalid();return; }
        }
        throw invalid();
    }
    private static void jpeg(byte[] bytes) {
        if(bytes.length<4||(bytes[0]&255)!=255||(bytes[1]&255)!=216) throw invalid();int cursor=2;boolean scan=false;
        while(cursor<bytes.length) {
            if(scan) { while(cursor<bytes.length&&(bytes[cursor]&255)!=255) cursor++; }
            if(cursor>=bytes.length||(bytes[cursor++]&255)!=255) throw invalid();while(cursor<bytes.length&&(bytes[cursor]&255)==255) cursor++;
            if(cursor>=bytes.length) throw invalid();int marker=bytes[cursor++]&255;
            if(scan&&(marker==0||marker>=208&&marker<=215)) continue;
            if(marker==217) { if(cursor!=bytes.length||!scan) throw invalid();return; }
            if(marker==216||marker==0||marker>=208&&marker<=215) throw invalid();
            if(cursor+2>bytes.length) throw invalid();int length=((bytes[cursor]&255)<<8)|(bytes[cursor+1]&255);if(length<2||length>bytes.length-cursor) throw invalid();
            cursor+=length;if(marker==218) scan=true;
        }
        throw invalid();
    }
    private static int u24(byte[] bytes,int offset) { return (bytes[offset]&255)|((bytes[offset+1]&255)<<8)|((bytes[offset+2]&255)<<16); }
    private static Dimensions webp(byte[] bytes) {
        if(bytes.length<20||!new String(bytes,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")||!new String(bytes,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP")) throw invalid();
        var input=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);if(Integer.toUnsignedLong(input.getInt(4))!=bytes.length-8) throw invalid();
        int cursor=12;Dimensions canvas=null,image=null;
        while(cursor+8<=bytes.length) {
            String chunk=new String(bytes,cursor,4,java.nio.charset.StandardCharsets.US_ASCII);int length=input.getInt(cursor+4),start=cursor+8;
            if(length<0||length>bytes.length-start) throw invalid();
            if(chunk.equals("VP8X")) { if(canvas!=null||cursor!=12||length!=10||(bytes[start]&2)!=0) throw invalid();canvas=new Dimensions(1+u24(bytes,start+4),1+u24(bytes,start+7)); }
            else if(chunk.equals("VP8 ")) {
                if(image!=null||length<=10||(bytes[start]&1)!=0||(bytes[start+3]&255)!=157||bytes[start+4]!=1||bytes[start+5]!=42) throw invalid();
                image=new Dimensions(Short.toUnsignedInt(input.getShort(start+6))&16383,Short.toUnsignedInt(input.getShort(start+8))&16383);
            } else if(chunk.equals("VP8L")) {
                if(image!=null||length<=5||(bytes[start]&255)!=47) throw invalid();int bits=input.getInt(start+1);if((bits>>>29)!=0) throw invalid();image=new Dimensions(1+(bits&16383),1+((bits>>>14)&16383));
            } else if(chunk.equals("ANIM")||chunk.equals("ANMF")) throw invalid();
            cursor=start+length+(length&1);if(cursor>bytes.length) throw invalid();
        }
        if(cursor!=bytes.length||image==null||canvas!=null&&!canvas.equals(image)) throw invalid();return image;
    }
}
