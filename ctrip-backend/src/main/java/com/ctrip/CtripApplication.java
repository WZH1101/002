package com.ctrip;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 悦途旅行预订平台后端入口
 * 模块：酒店 / 门票 / 火车票 / 机票 / 汽车票 + 订单中心
 */
@SpringBootApplication
public class CtripApplication {
    public static void main(String[] args) {
        SpringApplication.run(CtripApplication.class, args);
    }
}
