package com.ctrip.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基础参考数据（与前端/官网口径一致）：
 * 车站名、机场名、客运站名、铁路里程、经济舱全价、座位配置
 */
public final class Lookup {

    private Lookup() {}

    /** 城市列表（顺序与前端一致，用于无里程表时估算） */
    public static final List<String> CITIES = List.of(
            "北京", "上海", "广州", "深圳", "杭州", "南京", "成都", "重庆", "武汉", "西安",
            "郑州", "长沙", "天津", "青岛", "大连", "厦门", "昆明", "三亚", "桂林", "丽江");

    /** 火车站名（到发站与官网一致） */
    public static final Map<String, String> STATIONS = Map.ofEntries(
            Map.entry("北京", "北京南"), Map.entry("上海", "上海虹桥"), Map.entry("广州", "广州南"),
            Map.entry("深圳", "深圳北"), Map.entry("杭州", "杭州东"), Map.entry("南京", "南京南"),
            Map.entry("成都", "成都东"), Map.entry("重庆", "重庆北"), Map.entry("武汉", "武汉"),
            Map.entry("西安", "西安北"), Map.entry("郑州", "郑州东"), Map.entry("长沙", "长沙南"),
            Map.entry("天津", "天津西"), Map.entry("青岛", "青岛北"), Map.entry("大连", "大连北"),
            Map.entry("厦门", "厦门北"), Map.entry("昆明", "昆明南"), Map.entry("三亚", "三亚"),
            Map.entry("桂林", "桂林北"), Map.entry("丽江", "丽江"), Map.entry("济南", "济南西"));

    /** 机场名（与官网起降机场一致） */
    public static final Map<String, String> AIRPORTS = Map.ofEntries(
            Map.entry("北京", "北京首都T2"), Map.entry("上海", "上海虹桥T2"), Map.entry("广州", "广州白云T1"),
            Map.entry("深圳", "深圳宝安T3"), Map.entry("杭州", "杭州萧山T3"), Map.entry("南京", "南京禄口T2"),
            Map.entry("成都", "成都天府T2"), Map.entry("重庆", "重庆江北T3"), Map.entry("武汉", "武汉天河T3"),
            Map.entry("西安", "西安咸阳T3"), Map.entry("郑州", "郑州新郑T2"), Map.entry("长沙", "长沙黄花T2"),
            Map.entry("天津", "天津滨海T2"), Map.entry("青岛", "青岛胶东"), Map.entry("大连", "大连周水子"),
            Map.entry("厦门", "厦门高崎T4"), Map.entry("昆明", "昆明长水"), Map.entry("三亚", "三亚凤凰T1"),
            Map.entry("桂林", "桂林两江"), Map.entry("丽江", "丽江三义"), Map.entry("济南", "济南遥墙"));

    /** 汽车客运站名 */
    public static final Map<String, String> BUS_STATIONS = Map.ofEntries(
            Map.entry("北京", "北京六里桥客运站"), Map.entry("上海", "上海长途客运总站"), Map.entry("广州", "广东省汽车客运站"),
            Map.entry("深圳", "深圳银湖汽车站"), Map.entry("杭州", "杭州汽车客运中心"), Map.entry("南京", "南京汽车客运站"),
            Map.entry("成都", "成都新南门汽车站"), Map.entry("重庆", "重庆龙头寺汽车站"), Map.entry("武汉", "武汉傅家坡客运站"),
            Map.entry("西安", "西安城西客运站"), Map.entry("郑州", "郑州中心汽车站"), Map.entry("长沙", "长沙汽车东站"),
            Map.entry("天津", "天津通莎客运站"), Map.entry("青岛", "青岛长途汽车站"), Map.entry("大连", "大连北岗桥客运站"),
            Map.entry("厦门", "厦门枋湖汽车站"), Map.entry("昆明", "昆明东部客运站"), Map.entry("三亚", "三亚汽车总站"),
            Map.entry("桂林", "桂林汽车客运站"), Map.entry("丽江", "丽江客运站"), Map.entry("济南", "济南长途汽车总站"));

    /** 经济舱全价（官网公布运价，元），无表时按里程估算 */
    public static final Map<String, Integer> FULL_FARES = Map.ofEntries(
            Map.entry("北京-上海", 1490), Map.entry("上海-北京", 1490),
            Map.entry("北京-广州", 2310), Map.entry("广州-北京", 2310),
            Map.entry("北京-成都", 1740), Map.entry("上海-广州", 1850),
            Map.entry("上海-深圳", 1700), Map.entry("北京-深圳", 2380),
            Map.entry("深圳-北京", 2380), Map.entry("杭州-北京", 1390));

    /** 铁路里程表（公里，与官方公布里程一致） */
    public static final Map<String, Integer> RAIL_DISTANCES = new HashMap<>();
    static {
        String data = """
                北京-上海:1318 上海-北京:1318 北京-广州:2298 广州-北京:2298 北京-深圳:2400 深圳-北京:2400
                北京-武汉:1229 武汉-北京:1229 北京-西安:1216 西安-北京:1216 北京-杭州:1487 杭州-北京:1487
                北京-南京:1023 南京-北京:1023 北京-郑州:693 郑州-北京:693 北京-成都:1874 成都-北京:1874
                北京-重庆:2000 重庆-北京:2000 北京-长沙:1591 长沙-北京:1591 北京-天津:120 天津-北京:120
                北京-青岛:780 青岛-北京:780 上海-广州:1790 广州-上海:1790 上海-深圳:1940 深圳-上海:1940
                上海-武汉:800 武汉-上海:800 上海-杭州:169 杭州-上海:169 上海-南京:295 南京-上海:295
                上海-成都:1960 成都-上海:1960 上海-西安:1370 西安-上海:1370 上海-长沙:1073 长沙-上海:1073
                广州-武汉:1069 武汉-广州:1069 广州-深圳:147 深圳-广州:147 广州-长沙:707 长沙-广州:707
                广州-杭州:1240 杭州-广州:1240 深圳-武汉:1170 武汉-深圳:1170 深圳-长沙:850 长沙-深圳:850
                杭州-武汉:1000 武汉-杭州:1000 杭州-长沙:1000 长沙-杭州:1000 南京-武汉:540 武汉-南京:540
                西安-成都:658 成都-西安:658 郑州-西安:500 西安-郑州:500 天津-济南:300 济南-天津:300
                青岛-济南:400 济南-青岛:400 重庆-成都:320 成都-重庆:320""";
        for (String pair : data.split("\\s+")) {
            String[] kv = pair.split(":");
            RAIL_DISTANCES.put(kv[0], Integer.parseInt(kv[1]));
        }
    }

    /** 座位列配置（与 12306 座位类型一致，null 占位对齐列） */
    public static final Map<String, List<String>> SEAT_CONFIGS = Map.of(
            "G", List.of("商务座", "一等座", "二等座", "无座"),
            "D", List.of("", "一等座", "二等座", "无座"),
            "Z", List.of("软卧", "硬卧", "硬座", "无座"),
            "T", List.of("软卧", "硬卧", "硬座", "无座"),
            "K", List.of("软卧", "硬卧", "硬座", "无座"),
            "P", List.of("软卧", "硬卧", "硬座", "无座"));

    /** 车型名称 */
    public static final Map<String, String> TYPE_NAMES = Map.of(
            "G", "高铁", "D", "动车", "Z", "直达", "T", "特快", "K", "快速", "P", "普快");

    /** 获取铁路里程（无预置时按城市序号估算，与前端规则一致） */
    public static int distance(String from, String to) {
        Integer d = RAIL_DISTANCES.get(from + "-" + to);
        if (d != null) return d;
        int fi = CITIES.indexOf(from);
        int ti = CITIES.indexOf(to);
        if (fi < 0 || ti < 0) return 800;
        return 300 + Math.abs(fi - ti) * 260;
    }

    /** 站点名（无映射时用城市名） */
    public static String station(String city) {
        return STATIONS.getOrDefault(city, city);
    }

    public static String airport(String city) {
        return AIRPORTS.getOrDefault(city, city + "机场");
    }

    public static String busStation(String city) {
        return BUS_STATIONS.getOrDefault(city, city + "客运站");
    }
}
