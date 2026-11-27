package com.kurdman.app;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * Script-free, bounded HTML metadata extraction. Never fetches resources and
 * never honors page base tags, scripts, forms or event handlers. All extracted
 * URLs are untrusted and require per-request DNS/address/TLS validation.
 * This primitive is not connected to WebView or a source extension runtime.
 */
final class StaticWebPage {
    static final int MAX_HTML=2*1024*1024, MAX_NODES=20000, MAX_LINKS=200, MAX_IMAGES=300;
    static final class Link {
        final String label,url;
        Link(String label,String url){this.label=label;this.url=url;}
    }
    final String url,title;
    final List<Link> links;
    final List<String> images;
    final boolean truncated;
    private StaticWebPage(String url,String title,List<Link> links,List<String> images,boolean truncated){
        this.url=url;this.title=title;this.links=Collections.unmodifiableList(links);this.images=Collections.unmodifiableList(images);this.truncated=truncated;
    }
    static StaticWebPage singleImage(String finalUrl)throws IOException {
        String url=PinnedHttpsClient.checkedUrl(finalUrl).toASCIIString();
        return new StaticWebPage(url,"",new ArrayList<>(),new ArrayList<>(Collections.singletonList(url)),false);
    }
    static StaticWebPage parse(String finalUrl,String contentType,byte[] bytes)throws IOException {
        URI base=PinnedHttpsClient.checkedUrl(finalUrl);
        if(bytes==null||bytes.length==0||bytes.length>MAX_HTML||!utf8Html(contentType))throw new IOException("Unsupported HTML response");
        final String html;
        try{html=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException bad){throw new IOException("Invalid UTF-8 HTML",bad);}
        Document doc=Jsoup.parse(html); // parse from a string; never use jsoup's networking
        String title=clean(doc.title(),160);
        LinkedHashMap<String,String> links=new LinkedHashMap<>();LinkedHashMap<String,Boolean> images=new LinkedHashMap<>();
        Deque<Node> work=new ArrayDeque<>();work.push(doc);int seen=0;boolean capped=false;
        while(!work.isEmpty()){
            if(++seen>MAX_NODES){capped=true;break;}
            Node node=work.pop();
            if(node instanceof Element){
                Element e=(Element)node;String tag=e.normalName();
                if("script".equals(tag)||"style".equals(tag)||"template".equals(tag)||"iframe".equals(tag)||"svg".equals(tag)||"form".equals(tag))continue;
                if("a".equals(tag)){
                    String u=resolve(base,e.attr("href"));
                    if(u!=null&&!links.containsKey(u)){
                        if(links.size()>=MAX_LINKS)capped=true;
                        else {String label=label(e);links.put(u,label.isEmpty()?u:label);}
                    }
                }else if("img".equals(tag)){
                    String u=null;
                    // Common lazy-load attributes take precedence over placeholders.
                    for(String attr:new String[]{"data-src","data-original","data-lazy-src","src"}){
                        u=resolve(base,e.attr(attr));if(u!=null)break;
                    }
                    if(u!=null&&!images.containsKey(u)){
                        if(images.size()>=MAX_IMAGES)capped=true;else images.put(u,Boolean.TRUE);
                    }
                }
            }
            List<Node> children=node.childNodes();
            int allowed=Math.min(children.size(),Math.max(0,MAX_NODES-seen-work.size()));
            if(allowed<children.size())capped=true;
            for(int i=allowed-1;i>=0;i--)work.push(children.get(i));
        }
        List<Link> result=new ArrayList<>();for(java.util.Map.Entry<String,String> e:links.entrySet())result.add(new Link(e.getValue(),e.getKey()));
        return new StaticWebPage(base.toASCIIString(),title,result,new ArrayList<>(images.keySet()),capped);
    }
    private static boolean utf8Html(String value){
        if(value==null||value.length()>200)return false;
        String[] bits=value.toLowerCase(Locale.ROOT).split(";");String media=bits[0].trim();
        if(!"text/html".equals(media)&&!"application/xhtml+xml".equals(media))return false;
        for(int i=1;i<bits.length;i++){
            String part=bits[i].trim();if(part.startsWith("charset=")){
                String charset=part.substring(8).trim().replace("\"","");
                if(!"utf-8".equals(charset)&&!"utf8".equals(charset))return false;
            }
        }
        return true;
    }
    private static String resolve(URI base,String raw){
        if(raw==null||raw.isEmpty()||raw.length()>8192)return null;
        try{
            URI relative=new URI(raw.trim());if(relative.toString().isEmpty()||relative.toString().startsWith("#"))return null;
            URI resolved=base.resolve(relative);String value=resolved.toASCIIString();
            int hash=value.indexOf('#');if(hash>=0)value=value.substring(0,hash);
            return PinnedHttpsClient.checkedUrl(value).toASCIIString();
        }catch(Exception invalid){return null;}
    }
    private static String label(Element root){
        StringBuilder text=new StringBuilder();Deque<Node> work=new ArrayDeque<>();work.push(root);int nodes=0;
        while(!work.isEmpty()&&nodes++<500&&text.length()<256){
            Node n=work.pop();if(n instanceof TextNode)appendBounded(text,((TextNode)n).text());
            else if(n instanceof Element){Element e=(Element)n;String tag=e.normalName();
                if("script".equals(tag)||"style".equals(tag)||"template".equals(tag)||"svg".equals(tag))continue;
                if("img".equals(tag))appendBounded(text,e.attr("alt"));
            }
            List<Node> children=n.childNodes();int allowed=Math.min(children.size(),Math.max(0,500-nodes-work.size()));
            for(int i=allowed-1;i>=0;i--)work.push(children.get(i));
        }
        return clean(text.toString(),160);
    }
    private static void appendBounded(StringBuilder out,String s){
        if(out.length()>=256)return;out.append(' ');out.append(s,0,Math.min(s.length(),256-out.length()));
    }
    private static String clean(String input,int limit){
        StringBuilder out=new StringBuilder();boolean space=false;
        for(int i=0;i<input.length()&&out.length()<limit;){int cp=input.codePointAt(i);i+=Character.charCount(cp);
            if(Character.isISOControl(cp)||cp==0x061c||(cp>=0x200e&&cp<=0x200f)||(cp>=0x202a&&cp<=0x202e)||(cp>=0x2066&&cp<=0x2069))continue;
            if(Character.isWhitespace(cp)){space=out.length()>0;continue;}
            if(space&&out.length()<limit){out.append(' ');space=false;}
            if(out.length()+Character.charCount(cp)<=limit)out.appendCodePoint(cp);
        }return out.toString();
    }
}
