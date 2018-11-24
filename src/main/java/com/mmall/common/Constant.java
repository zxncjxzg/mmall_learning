package com.mmall.common;

import com.google.common.collect.Sets;

import java.util.Set;

/**
 * Created by 11790 on 2018/10/31.
 */
public class Constant {
    //session中的currentUser屬性常量
    public static final String CURRENT_USER="currentUser";

    public static final String USERNAME="username";
    public static final String EMAIL="email";

    //
    public interface ProductOrderBy{
        Set<String> PRICE_ASC_DESC= Sets.newHashSet("price_desc","price_asc");
    }

    public interface Cart{
        int CHECKED=1;// 购物车选中状态
        int UN_CHECKED=0; // 购物车未选中状态

        String LIMIT_NUM_FAIL="LIMIT_NUM_FAIL";
        String LIMIT_NUM_SUCCESS="LIMIT_NUM_SUCCESS";
    }

    public interface Role{
        int ROLE_CUSTOMER=1;//普通用戶
        int ROLE_ADMIN=0;//管理员
    }

    public enum ProductStatusEnum{
        ON_SALE(1,"在售");
        private String value;
        private int code;
        ProductStatusEnum(int code,String value){
            this.code=code;
            this.value=value;
        }

        public String getValue() {
            return value;
        }
        public int getCode() {
            return code;
        }
    }

    public enum ResponseCode {
        SUCCESS(0,"响应成功"),
        ERROR(1,"响应失败"),
        NEED_LOGIN(10,"需要登录"),
        ILLEGAL_ARGUMENT(2,"参数错误");


        private final int code;
        private final String desc;

        ResponseCode(int code, String desc){
            this.code=code;
            this.desc=desc;
        }
        public int getCode(){
            return code;
        }
        public String getDesc(){
            return desc;
        }
    }
}
