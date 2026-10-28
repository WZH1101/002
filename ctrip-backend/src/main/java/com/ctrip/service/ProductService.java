package com.ctrip.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 产品库服务：五模块搜索（查库，线路无数据时按官网规律生成并入库）
 * 以及下单时的服务端计价 + 库存扣减（防篡改，行锁保证一致性）
 */
@Service
public class ProductService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProductService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================== 搜索接口 ====================

    /** 酒店搜索：按城市，无数据时按品牌规律生成入库；keyword 过滤名称/商圈 */
    public List<Map<String, Object>> searchHotels(String city, String keyword) {
        ensureHotels(city);
        List<Map<String, Object>> list = query(
                "SELECT * FROM hotel WHERE city = ? ORDER BY stars DESC, base_price DESC", city);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : list) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("id", row.get("id"));
            h.put("name", row.get("name"));
            h.put("stars", row.get("stars"));
            h.put("score", row.get("score"));
            h.put("distance", row.get("distance"));
            h.put("area", row.get("area"));
            h.put("tags", parseList(row.get("tags")));
            h.put("basePrice", num(row.get("base_price")));
            h.put("rooms", parseList(row.get("rooms")));
            out.add(h);
        }
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            out.removeIf(h -> !String.valueOf(h.get("name")).contains(kw) && !String.valueOf(h.get("area")).contains(kw));
        }
        return out;
    }

    /** 景点门票搜索 */
    public List<Map<String, Object>> searchAttractions(String city, String keyword) {
        ensureAttractions(city);
        List<Map<String, Object>> list = query(
                "SELECT * FROM attraction WHERE city = ? ORDER BY base_price DESC", city);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : list) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", row.get("id"));
            t.put("name", row.get("name"));
            t.put("level", row.get("level"));
            t.put("score", row.get("score"));
            t.put("openTime", row.get("open_time"));
            t.put("area", row.get("area"));
            t.put("tags", parseList(row.get("tags")));
            t.put("basePrice", num(row.get("base_price")));
            t.put("ticketTypes", parseList(row.get("ticket_types")));
            out.add(t);
        }
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            out.removeIf(t -> !String.valueOf(t.get("name")).contains(kw));
        }
        return out;
    }

    /** 火车票搜索：真实车次库（12306 口径），无数据线路按规律生成入库 */
    public List<Map<String, Object>> searchTrains(String from, String to) {
        List<Map<String, Object>> rows = query(
                "SELECT * FROM train_ticket WHERE from_city = ? AND to_city = ? ORDER BY dep_time", from, to);
        if (rows.isEmpty()) {
            generateTrainsToDb(from, to);
            rows = query("SELECT * FROM train_ticket WHERE from_city = ? AND to_city = ? ORDER BY dep_time", from, to);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", row.get("id"));
            t.put("trainNo", row.get("train_no"));
            t.put("typeName", row.get("type_name"));
            t.put("fromStation", row.get("from_station"));
            t.put("toStation", row.get("to_station"));
            t.put("departureTime", row.get("dep_time"));
            t.put("arrivalTime", row.get("arr_time"));
            t.put("duration", row.get("duration"));
            t.put("seats", parseList(row.get("seats")));
            out.add(t);
        }
        return out;
    }

    /** 机票搜索 */
    public List<Map<String, Object>> searchFlights(String from, String to) {
        List<Map<String, Object>> rows = query(
                "SELECT * FROM flight WHERE from_city = ? AND to_city = ? ORDER BY dep_time", from, to);
        if (rows.isEmpty()) {
            generateFlightsToDb(from, to);
            rows = query("SELECT * FROM flight WHERE from_city = ? AND to_city = ? ORDER BY dep_time", from, to);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("id", row.get("id"));
            f.put("flightNo", row.get("flight_no"));
            f.put("airline", row.get("airline"));
            f.put("plane", row.get("plane"));
            f.put("fromAirport", row.get("from_airport"));
            f.put("toAirport", row.get("to_airport"));
            f.put("depTime", row.get("dep_time"));
            f.put("arrTime", row.get("arr_time"));
            f.put("duration", row.get("duration"));
            f.put("cabins", parseList(row.get("cabins")));
            out.add(f);
        }
        return out;
    }

    /** 汽车票搜索 */
    public List<Map<String, Object>> searchBuses(String from, String to) {
        List<Map<String, Object>> rows = query(
                "SELECT * FROM bus_ticket WHERE from_city = ? AND to_city = ? ORDER BY dep_time", from, to);
        if (rows.isEmpty()) {
            generateBusesToDb(from, to);
            rows = query("SELECT * FROM bus_ticket WHERE from_city = ? AND to_city = ? ORDER BY dep_time", from, to);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("id", row.get("id"));
            b.put("depTime", row.get("dep_time"));
            b.put("duration", row.get("duration"));
            b.put("fromStation", row.get("from_station"));
            b.put("toStation", row.get("to_station"));
            b.put("busType", row.get("bus_type"));
            b.put("price", num(row.get("price")));
            b.put("seats", row.get("seats"));
            out.add(b);
        }
        return out;
    }

    /** 船票搜索：客运航线有限，无结果时直接返回空列表（前端提示换热门航线） */
    public List<Map<String, Object>> searchShips(String from, String to) {
        List<Map<String, Object>> rows = query(
                "SELECT * FROM ship_ticket WHERE from_city = ? AND to_city = ? ORDER BY dep_time", from, to);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", row.get("id"));
            s.put("depTime", row.get("dep_time"));
            s.put("duration", row.get("duration"));
            s.put("fromPort", row.get("from_port"));
            s.put("toPort", row.get("to_port"));
            s.put("shipName", row.get("ship_name"));
            s.put("seatType", row.get("seat_type"));
            s.put("price", num(row.get("price")));
            s.put("seats", row.get("seats"));
            out.add(s);
        }
        return out;
    }

    // ==================== 新模块搜索（旅游/用车/礼品卡/全球购） ====================

    /** 度假线路：指定城市 + 全国通用线路 */
    public List<Map<String, Object>> searchVacations(String city) {
        List<Map<String, Object>> rows = query(
                "SELECT * FROM vacation_product WHERE city = ? OR city = '全国' ORDER BY score DESC", city);
        if (rows.isEmpty()) {
            rows = query("SELECT * FROM vacation_product WHERE city = '全国' ORDER BY score DESC");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", row.get("id"));
            v.put("name", row.get("name"));
            v.put("city", row.get("city"));
            v.put("days", row.get("days"));
            v.put("style", row.get("style"));
            v.put("score", row.get("score"));
            v.put("tags", parseList(row.get("tags")));
            v.put("price", num(row.get("price")));
            v.put("packages", parseList(row.get("packages")));
            out.add(v);
        }
        return out;
    }

    /** 租车用车：按城市，无数据时返回热门城市车型 */
    public List<Map<String, Object>> searchCars(String city) {
        List<Map<String, Object>> rows = query(
                "SELECT * FROM car_product WHERE city = ? ORDER BY price", city);
        if (rows.isEmpty()) {
            rows = query("SELECT * FROM car_product ORDER BY price LIMIT 8");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", row.get("id"));
            c.put("city", row.get("city"));
            c.put("brand", row.get("brand"));
            c.put("level", row.get("level"));
            c.put("price", num(row.get("price")));
            c.put("deposit", num(row.get("deposit")));
            c.put("stock", row.get("stock"));
            c.put("feature", row.get("feature"));
            out.add(c);
        }
        return out;
    }

    /** 礼品卡列表 */
    public List<Map<String, Object>> listGiftCards() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : query("SELECT * FROM gift_card ORDER BY sell_price")) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("id", row.get("id"));
            g.put("name", row.get("name"));
            g.put("faceValue", num(row.get("face_value")));
            g.put("sellPrice", num(row.get("sell_price")));
            g.put("stock", row.get("stock"));
            g.put("intro", row.get("intro"));
            out.add(g);
        }
        return out;
    }

    /** 全球购商品（category 为空返回全部） */
    public List<Map<String, Object>> listShopProducts(String category) {
        boolean all = category == null || category.isEmpty() || "全部".equals(category);
        List<Map<String, Object>> rows = all
                ? query("SELECT * FROM global_shop ORDER BY id")
                : query("SELECT * FROM global_shop WHERE category = ? ORDER BY id", category);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", row.get("id"));
            s.put("category", row.get("category"));
            s.put("name", row.get("name"));
            s.put("brand", row.get("brand"));
            s.put("price", num(row.get("price")));
            s.put("origin", row.get("origin"));
            s.put("stock", row.get("stock"));
            out.add(s);
        }
        return out;
    }

    // ==================== 下单计价 + 库存扣减（须在事务内调用） ====================

    /**
     * 根据服务端数据计算单价/总价并扣减库存。
     * 返回 {unitPrice, totalPrice}；库存不足抛 IllegalStateException
     */
    public Map<String, Object> priceAndDeduct(String type, long itemId, String choice, int qty, int days) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        switch (type) {
            case "hotel" -> {
                String json = lockJson("hotel", "rooms", itemId);
                List<Map<String, Object>> rooms = parse(json);
                Map<String, Object> room = findByName(rooms, choice);
                if (room == null) throw new IllegalStateException("房型不存在");
                double unit = dbl(room.get("price"));
                int count = (int) dbl(room.getOrDefault("count", 99));
                if (count < qty) throw new IllegalStateException("该房型库存不足");
                room.put("count", count - qty);
                updateJson("hotel", "rooms", itemId, toJson(rooms));
                return priceResult(unit, unit * qty * Math.max(1, days));
            }
            case "attraction", "ticket" -> {
                String json = lockJson("attraction", "ticket_types", itemId);
                List<Map<String, Object>> types = parse(json);
                Map<String, Object> tk = findByName(types, choice);
                if (tk == null) throw new IllegalStateException("票种不存在");
                double unit = dbl(tk.get("price"));
                return priceResult(unit, unit * qty);
            }
            case "train" -> {
                String json = lockJson("train_ticket", "seats", itemId);
                List<Map<String, Object>> seats = parse(json);
                Map<String, Object> seat = findByName(seats, choice);
                if (seat == null) throw new IllegalStateException("该座位类型不可预订");
                double unit = dbl(seat.get("price"));
                int count = (int) dbl(seat.getOrDefault("count", 0));
                if (count < qty) throw new IllegalStateException("余票不足（当前仅剩 " + count + " 张）");
                seat.put("count", count - qty);
                updateJson("train_ticket", "seats", itemId, toJson(seats));
                return priceResult(unit, unit * qty);
            }
            case "flight" -> {
                String json = lockJson("flight", "cabins", itemId);
                List<Map<String, Object>> cabins = parse(json);
                Map<String, Object> cabin = findByName(cabins, choice);
                if (cabin == null) throw new IllegalStateException("该舱位不可预订");
                double unit = dbl(cabin.get("price"));
                int count = (int) dbl(cabin.getOrDefault("count", 0));
                if (count < qty) throw new IllegalStateException("剩余票数不足（当前仅剩 " + count + " 张）");
                cabin.put("count", count - qty);
                updateJson("flight", "cabins", itemId, toJson(cabins));
                return priceResult(unit, unit * qty);
            }
            case "bus" -> {
                List<Map<String, Object>> rows = query(
                        "SELECT price, seats FROM bus_ticket WHERE id = ? FOR UPDATE", itemId);
                if (rows.isEmpty()) throw new IllegalStateException("班次不存在");
                double unit = dbl(rows.get(0).get("price"));
                int seats = (int) dbl(rows.get(0).get("seats"));
                if (seats < qty) throw new IllegalStateException("余票不足（当前仅剩 " + seats + " 张）");
                jdbc.update("UPDATE bus_ticket SET seats = seats - ? WHERE id = ?", qty, itemId);
                return priceResult(unit, unit * qty);
            }
            case "ship" -> {
                List<Map<String, Object>> rows = query(
                        "SELECT price, seats FROM ship_ticket WHERE id = ? FOR UPDATE", itemId);
                if (rows.isEmpty()) throw new IllegalStateException("航班不存在");
                double unit = dbl(rows.get(0).get("price"));
                int seats = (int) dbl(rows.get(0).get("seats"));
                if (seats < qty) throw new IllegalStateException("余票不足（当前仅剩 " + seats + " 张）");
                jdbc.update("UPDATE ship_ticket SET seats = seats - ? WHERE id = ?", qty, itemId);
                return priceResult(unit, unit * qty);
            }
            case "vacation" -> {
                String json = lockJson("vacation_product", "packages", itemId);
                List<Map<String, Object>> pkgs = parse(json);
                Map<String, Object> pkg = findByName(pkgs, choice);
                if (pkg == null) throw new IllegalStateException("套餐不存在");
                double unit = dbl(pkg.get("price"));
                int count = (int) dbl(pkg.getOrDefault("count", 0));
                if (count < qty) throw new IllegalStateException("套餐名额不足（仅剩 " + count + " 个）");
                pkg.put("count", count - qty);
                updateJson("vacation_product", "packages", itemId, toJson(pkgs));
                return priceResult(unit, unit * qty);
            }
            case "car" -> {
                List<Map<String, Object>> rows = query(
                        "SELECT price, stock FROM car_product WHERE id = ? FOR UPDATE", itemId);
                if (rows.isEmpty()) throw new IllegalStateException("车型不存在");
                double unit = dbl(rows.get(0).get("price"));
                int stock = (int) dbl(rows.get(0).get("stock"));
                if (stock < qty) throw new IllegalStateException("该车型已租完");
                jdbc.update("UPDATE car_product SET stock = stock - ? WHERE id = ?", qty, itemId);
                return priceResult(unit, unit * qty * Math.max(1, days));
            }
            case "shop" -> {
                List<Map<String, Object>> rows = query(
                        "SELECT price, stock FROM global_shop WHERE id = ? FOR UPDATE", itemId);
                if (rows.isEmpty()) throw new IllegalStateException("商品不存在");
                double unit = dbl(rows.get(0).get("price"));
                int stock = (int) dbl(rows.get(0).get("stock"));
                if (stock < qty) throw new IllegalStateException("库存不足（仅剩 " + stock + " 件）");
                jdbc.update("UPDATE global_shop SET stock = stock - ? WHERE id = ?", qty, itemId);
                return priceResult(unit, unit * qty);
            }
            case "gift" -> {
                List<Map<String, Object>> rows = query(
                        "SELECT sell_price, stock FROM gift_card WHERE id = ? FOR UPDATE", itemId);
                if (rows.isEmpty()) throw new IllegalStateException("礼品卡不存在");
                double unit = dbl(rows.get(0).get("sell_price"));
                int stock = (int) dbl(rows.get(0).get("stock"));
                if (stock < qty) throw new IllegalStateException("库存不足（仅剩 " + stock + " 张）");
                jdbc.update("UPDATE gift_card SET stock = stock - ? WHERE id = ?", qty, itemId);
                return priceResult(unit, unit * qty);
            }
            default -> throw new IllegalStateException("未知的预订类型: " + type);
        }
    }

    /**
     * 回补库存：取消订单时把下单扣掉的量加回去。
     * 与 {@link #priceAndDeduct} 成对出现——此前只有扣减没有回补，
     * 删掉订单（或清空订单）后库存永久减少，实测房型余量就是这么被磨掉的。
     * 同样走 SELECT ... FOR UPDATE，避免与并发下单互相覆盖 JSON 里的计数。
     */
    public void restoreStock(String type, long itemId, String choice, int qty) {
        if (qty <= 0) return;
        switch (type) {
            case "hotel" -> addBack("hotel", "rooms", itemId, choice, qty);
            case "train" -> addBack("train_ticket", "seats", itemId, choice, qty);
            case "flight" -> addBack("flight", "cabins", itemId, choice, qty);
            case "vacation" -> addBack("vacation_product", "packages", itemId, choice, qty);
            case "bus" -> jdbc.update("UPDATE bus_ticket SET seats = seats + ? WHERE id = ?", qty, itemId);
            case "ship" -> jdbc.update("UPDATE ship_ticket SET seats = seats + ? WHERE id = ?", qty, itemId);
            case "car" -> jdbc.update("UPDATE car_product SET stock = stock + ? WHERE id = ?", qty, itemId);
            case "shop" -> jdbc.update("UPDATE global_shop SET stock = stock + ? WHERE id = ?", qty, itemId);
            case "gift" -> jdbc.update("UPDATE gift_card SET stock = stock + ? WHERE id = ?", qty, itemId);
            // attraction：票种不占库存计数，无需回补
            default -> { }
        }
    }

    /** JSON 库存列里的某个 SKU 数量加回 qty；SKU 已被删除时静默跳过（不能凭空造一条） */
    private void addBack(String table, String column, long itemId, String choice, int qty) {
        String json = lockJson(table, column, itemId);
        List<Map<String, Object>> list = parse(json);
        Map<String, Object> sku = findByName(list, choice);
        if (sku == null) return;
        int count = (int) dbl(sku.getOrDefault("count", 0));
        sku.put("count", count + qty);
        updateJson(table, column, itemId, toJson(list));
    }

    private Map<String, Object> priceResult(double unit, double total) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unitPrice", tidy(unit));
        m.put("totalPrice", tidy(total));
        return m;
    }

    /** 金额取整化：整数去掉小数位，避免前端显示 662.0 */
    private static Object tidy(double v) {
        double r = Math.round(v * 100) / 100.0;
        return r == Math.floor(r) ? (Object) (long) r : (Object) r;
    }

    // ==================== 数据生成（无入库线路） ====================

    private void ensureHotels(String city) {
        Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM hotel WHERE city = ?", Integer.class, city);
        if (cnt != null && cnt > 0) return;
        String[][] brandGroups = {
                {"希尔顿", "万豪", "洲际", "威斯汀"},
                {"亚朵", "全季", "维也纳", "和颐"},
                {"汉庭", "如家", "7天", "格林豪泰"}};
        int[] stars = {5, 4, 2};
        int[] baseLo = {600, 300, 150};
        int[] baseHi = {1500, 500, 300};
        String[] areas = {"市中心", "火车站商圈", "滨江新区", "高新区", "老城区"};
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) {
            int g = i % 3;
            String[] brands = brandGroups[g];
            String brand = brands[i % brands.length];
            int basePrice = round(rnd.nextInt(baseLo[g], baseHi[g]) / 10.0) * 10;
            List<Map<String, Object>> rooms = buildRooms(basePrice, rnd);
            insertHotel(city, city + brand + "酒店", stars[g],
                    fmtScore(rnd, 4.2, 4.9), fmtDist(rnd, 0.5, 8), areas[i % 5],
                    List.of("免费WiFi", "含早餐", "停车场"), basePrice, rooms);
        }
    }

    private void ensureAttractions(String city) {
        Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM attraction WHERE city = ?", Integer.class, city);
        if (cnt != null && cnt > 0) return;
        String[] suffixes = {"古城", "海洋公园", "博物馆", "欢乐世界", "森林公园", "动物园"};
        String[] areas = {"市中心", "东郊", "西郊", "滨湖新区", "城南"};
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) {
            int price = round(rnd.nextInt(30, 200) / 5.0) * 5;
            insertAttraction(city, city + suffixes[i % suffixes.length],
                    i % 2 == 0 ? "5A景区" : "4A景区", fmtScore(rnd, 4.0, 4.9),
                    "08:30-17:30", areas[i % 5], List.of("亲子出游", "网红打卡", "快速入园").subList(0, 2),
                    price, buildTicketTypes(price));
        }
    }

    /** 按 12306 规律生成一条线路的车次并入库（G/D/Z/T/K 号段、平均旅速与真实一致） */
    private void generateTrainsToDb(String from, String to) {
        int dist = Lookup.distance(from, to);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int total = rnd.nextInt(10, 13);
        for (int i = 0; i < total; i++) {
            double roll = rnd.nextDouble();
            String type = roll < 0.5 ? "G" : (roll < 0.72 ? "D" : (roll < 0.86 ? "Z" : (roll < 0.93 ? "T" : "K")));
            String trainNo = switch (type) {
                case "G" -> "G" + rnd.nextInt(101, 2501);
                case "D" -> "D" + rnd.nextInt(1000, 7000);
                case "Z" -> "Z" + rnd.nextInt(1, 299);
                case "T" -> "T" + rnd.nextInt(1, 299);
                default -> "K" + rnd.nextInt(100, 1300);
            };
            int speed = switch (type) {
                case "G" -> 255;
                case "D" -> 165;
                case "Z" -> 105;
                default -> 78;
            };
            int durMin = (int) Math.round(dist / (double) speed * 60);
            int depMin = 6 * 60 + rnd.nextInt(17 * 60);
            int arrTotal = depMin + durMin;
            boolean nextDay = arrTotal >= 1440;
            String dep = fmtHm(depMin);
            String arr = (nextDay ? "次日 " : "") + fmtHm(arrTotal % 1440);
            Map<String, Double> prices = calcPrices(type, dist, false, null);
            List<Map<String, Object>> seats = buildSeats(type, prices, false, rnd);
            insertTrain(from, to, trainNo, type, dep, arr, durText(durMin), seats);
        }
    }

    /** 按航司/机型/折扣规律生成航班并入库 */
    private void generateFlightsToDb(String from, String to) {
        String[][] airlines = {{"CA", "中国国航"}, {"MU", "东方航空"}, {"CZ", "南方航空"}, {"HU", "海南航空"},
                {"HO", "吉祥航空"}, {"ZH", "深圳航空"}, {"MF", "厦门航空"}, {"SC", "山东航空"}};
        String[] planes = {"A320", "A320neo", "A321", "A330-300", "A350-900", "B737-800", "B787-9"};
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int rail = Lookup.distance(from, to);
        int dist = (int) Math.round(rail * 0.85);
        int durMin = (int) Math.round(dist / 780.0 * 60);
        int count = rnd.nextInt(6, 10);
        Integer fullFare = Lookup.FULL_FARES.get(from + "-" + to);
        for (int i = 0; i < count; i++) {
            String[] al = airlines[rnd.nextInt(airlines.length)];
            String plane = planes[rnd.nextInt(planes.length)];
            int depMin = 6 * 60 + rnd.nextInt(15 * 60);
            int arrTotal = depMin + durMin;
            boolean nextDay = arrTotal >= 1440;
            List<Map<String, Object>> cabins = buildCabins(
                    fullFare != null ? fullFare : 60 + (int) Math.round(dist * 1.2), plane, rnd);
            insertFlight(from, to, al[0] + rnd.nextInt(1000, 9000), al[1], plane,
                    fmtHm(depMin), (nextDay ? "次日 " : "") + fmtHm(arrTotal % 1440), durText(durMin), cabins);
        }
    }

    /** 按客运规律生成班次并入库（0.3元/公里、均速80km/h、06:30起40-90分钟一班） */
    private void generateBusesToDb(String from, String to) {
        int dist = (int) Math.round(Lookup.distance(from, to) * 1.15);
        int price = Math.max(20, (int) Math.round(dist * 0.3 / 5.0) * 5);
        int durMin = (int) Math.round(dist / 80.0 * 60);
        String[] busTypes = {"大型高一", "大型高二", "中型中级"};
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int count = rnd.nextInt(8, 13);
        int depMin = 6 * 60 + 30;
        for (int i = 0; i < count; i++) {
            insertBus(from, to, fmtHm(depMin), durText(durMin),
                    busTypes[i % busTypes.length], price, rnd.nextInt(5, 50));
            depMin += rnd.nextInt(40, 90);
            if (depMin >= 19 * 60 + 30) break;
        }
    }

    // ==================== 12306 / 民航 定价（与前端算法一致） ====================

    /** 分段递远递减：200km内全价，之后 9/8/7/6/5 折 */
    static double calcFare(double dist, double baseRate) {
        double[] caps = {200, 300, 500, 500, 500, Double.MAX_VALUE};
        double[] discs = {1, 0.9, 0.8, 0.7, 0.6, 0.5};
        double remain = dist, fare = 0;
        for (int i = 0; i < caps.length && remain > 0; i++) {
            double d = Math.min(remain, caps[i]);
            fare += d * baseRate * discs[i];
            remain -= d;
        }
        return Math.round(fare * 2) / 2.0;
    }

    /** key: business/first/second/softSleeper/hardSleeper/hardSeat/none；京沪高铁特判与官网一致 */
    static Map<String, Double> calcPrices(String type, int dist, boolean flagship, String trainNo) {
        Map<String, Double> p = new HashMap<>();
        if ("G".equals(type)) {
            if (dist == 1318) {
                p.put("business", 1748.0);
                p.put("first", flagship ? 1061.0 : 933.0);
                p.put("second", flagship ? 662.0 : 553.0);
            } else {
                p.put("second", calcFare(dist, 0.46));
                p.put("first", calcFare(dist, 0.73));
                p.put("business", calcFare(dist, 1.38));
            }
            p.put("none", p.get("second"));
        } else if ("D".equals(type)) {
            p.put("second", calcFare(dist, 0.31));
            p.put("first", calcFare(dist, 0.37));
            p.put("softSleeper", calcFare(dist, 0.61));
            p.put("none", p.get("second"));
        } else {
            p.put("hardSeat", calcFare(dist, 0.15));
            p.put("hardSleeper", calcFare(dist, 0.25));
            p.put("softSleeper", calcFare(dist, 0.41));
            p.put("none", p.get("hardSeat"));
        }
        return p;
    }

    /** 座位数组（4列对齐，null 占位；动卧为 软卧/null/二等座/无座） */
    List<Map<String, Object>> buildSeats(String type, Map<String, Double> prices, boolean sleeping, ThreadLocalRandom rnd) {
        List<String> names = sleeping ? List.of("软卧", "", "二等座", "无座") : Lookup.SEAT_CONFIGS.get(type);
        List<Map<String, Object>> seats = new ArrayList<>();
        for (String name : names) {
            if (name.isEmpty()) {
                seats.add(null);
                continue;
            }
            Double price = switch (name) {
                case "商务座" -> prices.get("business");
                case "软卧" -> prices.get("softSleeper");
                case "一等座" -> prices.get("first");
                case "硬卧" -> prices.get("hardSleeper");
                case "二等座" -> prices.get("second");
                case "硬座" -> prices.get("hardSeat");
                default -> prices.get("none");
            };
            int count = switch (name) {
                case "二等座" -> rnd.nextInt(50, 400);
                case "硬座" -> rnd.nextInt(100, 500);
                default -> rnd.nextInt(90);
            };
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", name);
            s.put("price", price);
            s.put("count", count);
            seats.add(s);
        }
        return seats;
    }

    List<Map<String, Object>> buildCabins(int fullFare, String plane, ThreadLocalRandom rnd) {
        double discount = 0.35 + rnd.nextDouble() * 0.45; // 经济舱 3.5-8 折
        int eco = (int) Math.round(fullFare * discount / 10.0) * 10;
        boolean hasFirst = plane.startsWith("A330") || plane.startsWith("A350")
                || plane.startsWith("B777") || plane.startsWith("B787");
        List<Map<String, Object>> cabins = new ArrayList<>();
        cabins.add(cabin("经济舱", eco, rnd.nextInt(20, 200)));
        cabins.add(cabin("公务舱", (int) Math.round(fullFare * 2.8 / 10.0) * 10, rnd.nextInt(20)));
        if (hasFirst) {
            cabins.add(cabin("头等舱", (int) Math.round(fullFare * 3.8 / 10.0) * 10, rnd.nextInt(12)));
        }
        return cabins;
    }

    List<Map<String, Object>> buildRooms(int basePrice, ThreadLocalRandom rnd) {
        List<Map<String, Object>> rooms = new ArrayList<>();
        rooms.add(sku("大床房", "1.8米大床 · 25㎡ · 含早餐", basePrice, rnd.nextInt(5, 30)));
        rooms.add(sku("双床房", "两张1.2米床 · 28㎡ · 含早餐", basePrice + 80, rnd.nextInt(5, 30)));
        rooms.add(sku("豪华套房", "独立客厅 · 55㎡ · 含双早", (int) Math.round(basePrice * 2.2 / 10.0) * 10, rnd.nextInt(2, 12)));
        return rooms;
    }

    List<Map<String, Object>> buildTicketTypes(int price) {
        List<Map<String, Object>> types = new ArrayList<>();
        types.add(sku("成人票", "适用于1.5米以上游客", price, 0));
        types.add(sku("儿童票", "身高1.2-1.5米（半价）", Math.round(price * 0.5), 0));
        types.add(sku("学生票", "凭有效学生证（约6折）", Math.round(price * 0.6), 0));
        return types;
    }

    // ==================== 入库与通用工具 ====================

    private void insertHotel(String city, String name, int stars, String score, String distance,
                             String area, List<String> tags, int basePrice, List<Map<String, Object>> rooms) {
        jdbc.update("INSERT INTO hotel(city,name,stars,score,distance,area,tags,base_price,rooms) VALUES(?,?,?,?,?,?,?,?,?)",
                city, name, stars, score, distance, area, toJson(tags), basePrice, toJson(rooms));
    }

    private void insertAttraction(String city, String name, String level, String score, String openTime,
                                  String area, List<String> tags, int price, List<Map<String, Object>> types) {
        jdbc.update("INSERT INTO attraction(city,name,level,score,open_time,area,tags,base_price,ticket_types) VALUES(?,?,?,?,?,?,?,?,?)",
                city, name, level, score, openTime, area, toJson(tags), price, toJson(types));
    }

    private void insertTrain(String from, String to, String trainNo, String type, String dep, String arr,
                             String duration, List<Map<String, Object>> seats) {
        jdbc.update("INSERT INTO train_ticket(from_city,to_city,train_no,type_code,type_name,from_station,to_station,dep_time,arr_time,duration,seats) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                from, to, trainNo, type, Lookup.TYPE_NAMES.get(type), Lookup.station(from), Lookup.station(to),
                dep, arr, duration, toJson(seats));
    }

    private void insertFlight(String from, String to, String flightNo, String airline, String plane,
                              String dep, String arr, String duration, List<Map<String, Object>> cabins) {
        jdbc.update("INSERT INTO flight(from_city,to_city,flight_no,airline,plane,from_airport,to_airport,dep_time,arr_time,duration,cabins) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                from, to, flightNo, airline, plane, Lookup.airport(from), Lookup.airport(to),
                dep, arr, duration, toJson(cabins));
    }

    private void insertBus(String from, String to, String dep, String duration, String busType, int price, int seats) {
        jdbc.update("INSERT INTO bus_ticket(from_city,to_city,dep_time,from_station,to_station,duration,bus_type,price,seats) VALUES(?,?,?,?,?,?,?,?,?)",
                from, to, dep, Lookup.busStation(from), Lookup.busStation(to), duration, busType, price, seats);
    }

    private String lockJson(String table, String column, long id) {
        List<String> rows = jdbc.queryForList(
                "SELECT " + column + " FROM " + table + " WHERE id = ? FOR UPDATE", String.class, id);
        if (rows.isEmpty() || rows.get(0) == null) throw new IllegalStateException("记录不存在");
        return rows.get(0);
    }

    private void updateJson(String table, String column, long id, String json) {
        jdbc.update("UPDATE " + table + " SET " + column + " = ? WHERE id = ?", json, id);
    }

    private Map<String, Object> findByName(List<Map<String, Object>> list, String name) {
        for (Map<String, Object> m : list) {
            if (m != null && Objects.equals(m.get("name"), name)) return m;
        }
        return null;
    }

    private Map<String, Object> sku(String name, String desc, Number price, int count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("desc", desc);
        m.put("price", price);
        m.put("count", count);
        return m;
    }

    private Map<String, Object> cabin(String name, Number price, int count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("price", price);
        m.put("count", count);
        return m;
    }

    private List<Map<String, Object>> query(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    List<Map<String, Object>> parse(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException("JSON 解析失败", e);
        }
    }

    /** JSON 列 → List（兼容字符串数组与对象数组，对象数组可含 null 占位，如火车座位列） */
    private Object parseList(Object json) {
        if (json == null) return List.of();
        try {
            return mapper.readValue(String.valueOf(json), Object.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    static String fmtHm(int totalMin) {
        return String.format("%02d:%02d", totalMin / 60 % 24, totalMin % 60);
    }

    static String durText(int min) {
        return min / 60 + "时" + min % 60 + "分";
    }

    private static String fmtScore(ThreadLocalRandom rnd, double lo, double hi) {
        return String.format("%.1f", lo + rnd.nextDouble() * (hi - lo));
    }

    private static String fmtDist(ThreadLocalRandom rnd, double lo, double hi) {
        return String.format("%.1f", lo + rnd.nextDouble() * (hi - lo));
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static int round(double v) {
        return (int) Math.round(v);
    }

    private static double dbl(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }

    private static Object num(Object o) {
        if (o instanceof java.math.BigDecimal bd) {
            return bd.stripTrailingZeros().scale() <= 0 ? bd.longValue() : bd.doubleValue();
        }
        return o;
    }
}
