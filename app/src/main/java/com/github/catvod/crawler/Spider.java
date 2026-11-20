package com.github.catvod.crawler;

import android.content.Context;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Binary compatibility surface for the classic TVBox Spider ABI. */
public abstract class Spider {
    public static okhttp3.Dns safeDns() { return okhttp3.Dns.SYSTEM; }
    public void init(Context context) throws Exception {}
    public void init(Context context, String extend) throws Exception { init(context); }
    public String homeContent(boolean filter) throws Exception { return "{\"class\":[]}"; }
    public String homeVideoContent() throws Exception { return "{\"list\":[]}"; }
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception { return "{\"list\":[]}"; }
    public String detailContent(List<String> ids) throws Exception { return "{\"list\":[]}"; }
    public String searchContent(String key, boolean quick) throws Exception { return "{\"list\":[]}"; }
    public String searchContent(String key, boolean quick, String pg) throws Exception { return searchContent(key, quick); }
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception { return "{}"; }
    public boolean isVideoFormat(String url) { return false; }
    public boolean manualVideoCheck() { return false; }
    public Object[] proxy(Map<String, String> params) throws Exception { return null; }
    public void destroy() {}
}
