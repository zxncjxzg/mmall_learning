package com.mmall.service.impl;

import com.alipay.api.AlipayResponse;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.demo.trade.config.Configs;
import com.alipay.demo.trade.model.ExtendParams;
import com.alipay.demo.trade.model.GoodsDetail;
import com.alipay.demo.trade.model.builder.AlipayTradePrecreateRequestBuilder;
import com.alipay.demo.trade.model.result.AlipayF2FPrecreateResult;
import com.alipay.demo.trade.service.AlipayTradeService;
import com.alipay.demo.trade.service.impl.AlipayTradeServiceImpl;
import com.alipay.demo.trade.utils.ZxingUtils;
import com.google.common.collect.Lists;
import com.mmall.common.Constant;
import com.mmall.common.ServerResponse;
import com.mmall.dao.*;
import com.mmall.pojo.*;
import com.mmall.service.IOrderService;
import com.mmall.util.BigDecimalUtil;
import com.mmall.util.DateTimeUtil;
import com.mmall.util.FTPUtil;
import com.mmall.util.PropertiesUtil;
import com.mmall.vo.OrderItemVo;
import com.mmall.vo.OrderVo;
import com.mmall.vo.ShippingVo;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

/**
 * Created by 11790 on 2018/11/30.
 */
@Service("iOrderService")
public class OrderServiceImpl implements IOrderService {

    private static final Logger logger= LoggerFactory.getLogger(OrderServiceImpl.class);

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private PayInfoMapper payInfoMapper;
    @Autowired
    private CartMapper cartMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private ShippingMapper shippingMapper;

    public ServerResponse<OrderVo> createOrder(Integer userId,Integer shippingId){
        //1.从购物车中获取已勾选的购物车数据
        List<Cart> cartList=cartMapper.selectByCheckedAndUserId(userId);
        //2.计算订单的总价
        ServerResponse serverResponse=this.getOrderItem(userId,cartList);
        if (!serverResponse.isSuccess()){
            return serverResponse;
        }
        List<OrderItem> orderItemList=(List<OrderItem>)serverResponse.getData();
        BigDecimal payment=this.getOrderTotalPrice(orderItemList);
        //3.生成订单
        Order order=this.assembleOrder(userId,shippingId,payment);
        if(order==null){
            return ServerResponse.createByErrorMessage("生成订单错误");
        }
        if(CollectionUtils.isEmpty(orderItemList)){
            return ServerResponse.createByErrorMessage("购物车为空");
        }
        for(OrderItem orderItem:orderItemList){
            orderItem.setOrderNo(order.getOrderNo());
        }
        //4.mybatis批量插入OrderItem
        orderItemMapper.batchInsert(orderItemList);
        //5.生成成功，减少产品的库存
        this.reduceProductStock(orderItemList);
        //6.清空购物车
        this.cleanCart(cartList);
        //7.返回给前端OrderVO对象
        OrderVo orderVo=assembleOrderVo(order,orderItemList);
        return ServerResponse.createBySuccess(orderVo);
    }

    //组装返回给前端的OrderVO对象
    private OrderVo assembleOrderVo(Order order,List<OrderItem> orderItemList){
        OrderVo orderVo=new OrderVo();

        orderVo.setOrderNo(order.getOrderNo());
        orderVo.setPayment(order.getPayment());//实际付款金额
        orderVo.setPaymentType(order.getPaymentType());//支付类型
        orderVo.setPaymentTypeDesc(Constant.PaymentTypeEnum.codeOf(order.getPaymentType()).getValue());//支付类型描述

        orderVo.setPostage(order.getPostage());
        orderVo.setStatus(order.getStatus());
        orderVo.setStatusDesc(Constant.OrderStatusEnum.codeOf(order.getStatus()).getValue());

        orderVo.setShippingId(order.getShippingId());
        Shipping shipping=shippingMapper.selectByPrimaryKey(order.getShippingId());
        if(shipping!=null){
            orderVo.setReceiverName(shipping.getReceiverName());
            orderVo.setShippingVo(assembleShippingVo(shipping));
        }

        orderVo.setPaymentTime(DateTimeUtil.dateToStr(order.getPaymentTime()));
        orderVo.setSendTime(DateTimeUtil.dateToStr(order.getSendTime()));
        orderVo.setEndTime(DateTimeUtil.dateToStr(order.getEndTime()));
        orderVo.setCreateTime(DateTimeUtil.dateToStr(order.getCreateTime()));
        orderVo.setCloseTime(DateTimeUtil.dateToStr(order.getCloseTime()));

        orderVo.setImageHost(PropertiesUtil.getProperty("ftp.server.http.prefix"));

        List<OrderItemVo> orderItemVoList = Lists.newArrayList();
        for(OrderItem orderItem : orderItemList){
            OrderItemVo orderItemVo = assembleOrderItemVo(orderItem);
            orderItemVoList.add(orderItemVo);
        }
        orderVo.setOrderItemVoList(orderItemVoList);

        return orderVo;
    }

    private OrderItemVo assembleOrderItemVo(OrderItem orderItem){
        OrderItemVo orderItemVo = new OrderItemVo();
        orderItemVo.setOrderNo(orderItem.getOrderNo());
        orderItemVo.setProductId(orderItem.getProductId());
        orderItemVo.setProductName(orderItem.getProductName());
        orderItemVo.setProductImage(orderItem.getProductImage());
        orderItemVo.setCurrentUnitPrice(orderItem.getCurrentUnitPrice());
        orderItemVo.setQuantity(orderItem.getQuantity());
        orderItemVo.setTotalPrice(orderItem.getTotalPrice());

        orderItemVo.setCreateTime(DateTimeUtil.dateToStr(orderItem.getCreateTime()));
        return orderItemVo;
    }

    private ShippingVo assembleShippingVo(Shipping shipping){
        ShippingVo shippingVo = new ShippingVo();
        shippingVo.setReceiverName(shipping.getReceiverName());
        shippingVo.setReceiverAddress(shipping.getReceiverAddress());
        shippingVo.setReceiverProvince(shipping.getReceiverProvince());
        shippingVo.setReceiverCity(shipping.getReceiverCity());
        shippingVo.setReceiverDistrict(shipping.getReceiverDistrict());
        shippingVo.setReceiverMobile(shipping.getReceiverMobile());
        shippingVo.setReceiverZip(shipping.getReceiverZip());
        shippingVo.setReceiverPhone(shippingVo.getReceiverPhone());
        return shippingVo;
    }

    //清空购物车
    private void cleanCart(List<Cart> cartList){
        for(Cart cart:cartList){
            cartMapper.deleteByPrimaryKey(cart.getId());
        }
    }

    //减少产品库存
    private void reduceProductStock(List<OrderItem> orderItemList){
        for(OrderItem orderItem:orderItemList){
            Product product=productMapper.selectByPrimaryKey(orderItem.getProductId());
            product.setStock(product.getStock()-orderItem.getQuantity());
            productMapper.updateByPrimaryKeySelective(product);
        }
    }

    //组装订单,并且存入数据库中
    private Order assembleOrder(Integer userId,Integer shippingId,BigDecimal payment){
        Order order=new Order();
        long orderNo=this.genernateOrderNo();
        order.setUserId(userId);
        order.setShippingId(shippingId);
        order.setOrderNo(orderNo);
        order.setStatus(Constant.OrderStatusEnum.NO_PAY.getCode());
        order.setPostage(0);
        order.setPaymentType(Constant.PaymentTypeEnum.ONLINE_PAY.getCode());
        order.setPayment(payment);

        int rowCount=orderMapper.insert(order);
        if(rowCount>0){
            return order;
        }
        return null;
    }

    //生成订单号
    private long genernateOrderNo(){
        long currentTime=System.currentTimeMillis();
        return currentTime+new Random().nextInt(10);//new Random().nextInt(10)会生成0~9的随机数，可以避免由于并发生成重复的订单号
    }

    //计算订单的总价格
    private BigDecimal getOrderTotalPrice(List<OrderItem> orderItemList){
        BigDecimal payment=new BigDecimal("0");
        for(OrderItem orderItem:orderItemList){
            payment=BigDecimalUtil.add(payment.doubleValue(),orderItem.getTotalPrice().doubleValue());
        }
        return payment;
    }

    /**
     *
     * @param userId
     * @param cartList
     * @return
     */
    private ServerResponse<List<OrderItem>> getOrderItem(Integer userId,List<Cart> cartList){
        List<OrderItem> orderItemList=Lists.newArrayList();
        if(CollectionUtils.isEmpty(cartList)){
            return ServerResponse.createByErrorMessage("购物车为空");
        }
        for(Cart cartItem:cartList){
            OrderItem orderItem=new OrderItem();
            Product product=productMapper.selectByPrimaryKey(cartItem.getProductId());
            //1.校验购物车中的产品是否处于售卖状态
            if(Constant.ProductStatusEnum.ON_SALE.getCode()!=product.getStatus()){
                return ServerResponse.createByErrorMessage("产品"+product.getName()+"不在售卖状态");
            }
            //2.校验库存
            if(cartItem.getQuantity()>product.getStock()){
                return ServerResponse.createByErrorMessage("产品"+product.getName()+"库存不足");
            }
            //3.组装orderItem
            orderItem.setUserId(userId);
            orderItem.setProductId(product.getId());
            orderItem.setProductName(product.getName());
            orderItem.setProductImage(product.getMainImage());
            orderItem.setCurrentUnitPrice(product.getPrice());
            orderItem.setQuantity(cartItem.getQuantity());
            orderItem.setTotalPrice(BigDecimalUtil.mul(product.getPrice().doubleValue(),cartItem.getQuantity()));
            orderItemList.add(orderItem);
        }
        return ServerResponse.createBySuccess(orderItemList);
    }


    /**
     *  支付
     * @param orderNo 订单号
     * @param userId 用户ID
     * @param path upload路径地址
     * @return
     */
    public ServerResponse pay(Long orderNo,Integer userId,String path){
        Map<String,String> resultMap=new HashMap();
        //1.根据用户ID和订单号获取订单
        Order order=orderMapper.selectByUserIdAndOrderNo(userId,orderNo);
        if(order==null){
            return ServerResponse.createByErrorMessage("用户没有该订单");
        }
        resultMap.put("orderNo",String.valueOf(order.getOrderNo()));


        // (必填) 商户网站订单系统中唯一订单号，64个字符以内，只能包含字母、数字、下划线，
        // 需保证商户系统端不能重复，建议通过数据库sequence生成，
        //2.1 设置支付宝交易订单号
        String outTradeNo = order.getOrderNo().toString();

        // (必填) 订单标题，粗略描述用户的支付目的。如“xxx品牌xxx门店当面付扫码消费”
        //2.2 设置订单标题
        String subject = new StringBuilder().append("happymmall扫码支付，订单号：").append(outTradeNo).toString();

        // (必填) 订单总金额，单位为元，不能超过1亿元
        // 如果同时传入了【打折金额】,【不可打折金额】,【订单总金额】三者,则必须满足如下条件:【订单总金额】=【打折金额】+【不可打折金额】
        //2.3 设置订单总金额
        String totalAmount = order.getPayment().toString();

        // (可选) 订单不可打折金额，可以配合商家平台配置折扣活动，如果酒水不参与打折，则将对应金额填写至此字段
        // 如果该值未传入,但传入了【订单总金额】,【打折金额】,则该值默认为【订单总金额】-【打折金额】
        //2.4 设置不能打折金额
        String undiscountableAmount = "0";

        // 卖家支付宝账号ID，用于支持一个签约账号下支持打款到不同的收款账号，(打款到sellerId对应的支付宝账号)
        // 如果该字段为空，则默认为与支付宝签约的商户的PID，也就是appid对应的PID
        String sellerId = "";

        // 订单描述，可以对交易或商品进行一个详细地描述，比如填写"购买商品2件共15.00元"
        //2.5 设置订单描述
        String body = new StringBuilder().append("订单").append(outTradeNo).append("购买商品共").append(totalAmount).append("元").toString();

        // 商户操作员编号，添加此参数可以为商户操作员做销售统计
        String operatorId = "test_operator_id";

        // (必填) 商户门店编号，通过门店号和商家后台可以配置精准到门店的折扣信息，详询支付宝技术支持
        String storeId = "test_store_id";

        // 业务扩展参数，目前可添加由支付宝分配的系统商编号(通过setSysServiceProviderId方法)，详情请咨询支付宝技术支持
        ExtendParams extendParams = new ExtendParams();
        extendParams.setSysServiceProviderId("2088100200300400500");


        // 支付超时，定义为120分钟
        String timeoutExpress = "120m";

        // 商品明细列表，需填写购买商品详细信息，
        //2.6 设置商品明细列表
        List<GoodsDetail> goodsDetailList = new ArrayList<GoodsDetail>();
        List<OrderItem> orderItemList=orderItemMapper.selectByUserIdAndOrderNo(userId,orderNo);//获取订单明细
        for(OrderItem orderItem:orderItemList) {
            GoodsDetail goodsDetail=GoodsDetail.newInstance(orderItem.getProductId().toString(),orderItem.getProductName(),
                    BigDecimalUtil.mul(orderItem.getCurrentUnitPrice().doubleValue(),new Double(100).doubleValue()).longValue(),orderItem.getQuantity());
            goodsDetailList.add(goodsDetail);
        }

        // 2.7 创建扫码支付请求builder，设置请求参数
        AlipayTradePrecreateRequestBuilder builder = new AlipayTradePrecreateRequestBuilder()
                .setSubject(subject).setTotalAmount(totalAmount).setOutTradeNo(outTradeNo)
                .setUndiscountableAmount(undiscountableAmount).setSellerId(sellerId).setBody(body)
                .setOperatorId(operatorId).setStoreId(storeId).setExtendParams(extendParams)
                .setTimeoutExpress(timeoutExpress)
                .setNotifyUrl(PropertiesUtil.getProperty("alipay.callback.url"))//支付宝服务器主动通知商户服务器里指定的页面http路径,根据需要设置
                .setGoodsDetailList(goodsDetailList);

        /** 2.8 一定要在创建AlipayTradeService之前调用Configs.init()设置默认参数
         *  Configs会读取classpath下的zfbinfo.properties文件配置信息，如果找不到该文件则确认该文件是否在classpath目录
         */
        Configs.init("zfbinfo.properties");

        /** 2.9 使用Configs提供的默认参数
         *  AlipayTradeService可以使用单例或者为静态成员对象，不需要反复new
         */
        AlipayTradeService tradeService = new AlipayTradeServiceImpl.ClientBuilder().build();
        //2.10 预下单结果
        AlipayF2FPrecreateResult result = tradeService.tradePrecreate(builder);
        switch (result.getTradeStatus()) {
            case SUCCESS:
                logger.info("支付宝预下单成功: )");
                //2.10.1 获取支付宝返回的结果对象中的response对象
                AlipayTradePrecreateResponse response = result.getResponse();
                //2.10.2 在日志中简单打印应答
                dumpResponse(response);
                //2.10.3 创建二维码存放文件夹
                File folder=new File(path);
                if(!folder.exists()){
                    folder.setWritable(true);
                    folder.mkdirs();
                }

                // 2.10.4 二维码图片存放路径
                String qrPath = String.format(path+"/qr-%s.png", response.getOutTradeNo());
                //2.10.5 二维码图片文件名称
                String qrFileName=String.format("qr-%s.png",response.getOutTradeNo());
                //2.10.6 生成二维码
                ZxingUtils.getQRCodeImge(response.getQrCode(), 256, qrPath);
                //2.10.7 将二维码上传至FTP服务器，并且设置二维码的URL
                File targetFile=new File(path,qrFileName);
                try {
                    FTPUtil.uploadFile(Lists.<File>newArrayList(targetFile));//将二维码上传至FTP服务器
                } catch (IOException e) {
                    logger.error("上传二维码异常",e);
                }
                logger.info("qrPath:" + qrPath);
                String qrUrl=PropertiesUtil.getProperty("ftp.server.http.prefix")+targetFile.getName();
                resultMap.put("qrUrl",qrUrl);
                return ServerResponse.createBySuccess(resultMap);
            case FAILED:
                logger.error("支付宝预下单失败!!!");
                return ServerResponse.createByErrorMessage("支付宝预下单失败!!!");
            case UNKNOWN:
                logger.error("系统异常，预下单状态未知!!!");
                return ServerResponse.createByErrorMessage("系统异常，预下单状态未知!!!");
            default:
                logger.error("不支持的交易状态，交易返回异常!!!");
                return ServerResponse.createByErrorMessage("不支持的交易状态，交易返回异常!!!");
        }
    }
    // 简单打印应答
    private void dumpResponse(AlipayResponse response) {
        if (response != null) {
            logger.info(String.format("code:%s, msg:%s", response.getCode(), response.getMsg()));
            if (StringUtils.isNotEmpty(response.getSubCode())) {
                logger.info(String.format("subCode:%s, subMsg:%s", response.getSubCode(),
                        response.getSubMsg()));
            }
            logger.info("body:" + response.getBody());
        }
    }

    /**
     * 支付宝回调，应该是在扫码支付后进行回调，在系统中存储支付信息
     * @param params
     * @return
     */
    public ServerResponse alipayCallback(Map<String,String> params){
        Long orderNo=Long.parseLong(params.get("out_trade_no"));
        String tradeNo=params.get("trade_no");
        String tradeStatus=params.get("trade_status");
        //1.根据支付宝传回来的订单号查询订单，如果订单不存在，则提示“非本商城的订单，回调忽略”
        Order order=orderMapper.selectByOrderNo(orderNo);
        if(order==null){
            return ServerResponse.createByErrorMessage("非本商城的订单，回调忽略");
        }
        //2.验证订单的状态，如果已经支付了，则提示“支付宝重复调用”
        if(order.getStatus()>= Constant.OrderStatusEnum.PAID.getCode()){
            return ServerResponse.createBySuccess("支付宝重复调用");
        }
        //3.如果tradeStatus等于TRADE_SUCCESS，则设置订单状态为(20,"已付款")
        if(Constant.AlipayCallback.TRADE_STATUS_TRADE_SUCCESS.equals(tradeStatus)){
            order.setPaymentTime(DateTimeUtil.strToDate(params.get("gmt_payment")));//设置支付时间,gmt_payment为支付宝支付时间
            order.setStatus(Constant.OrderStatusEnum.PAID.getCode());
            orderMapper.updateByPrimaryKeySelective(order);
        }
        //4.存储支付信息
        PayInfo payInfo=new PayInfo();
        payInfo.setUserId(order.getUserId());
        payInfo.setOrderNo(orderNo);
        payInfo.setPayPlatform(Constant.PayPlatformEnum.ALIPAY.getCode());
        payInfo.setPlatformNumber(tradeNo);
        payInfo.setPlatformStatus(tradeStatus);
        payInfoMapper.insert(payInfo);
        //5.返回成功信息
        return ServerResponse.createBySuccess();
    }

    public ServerResponse queryOrderPayStatus(Integer userId,Long orderNo){
        Order order=orderMapper.selectByUserIdAndOrderNo(userId,orderNo);
        if(order==null){
            return ServerResponse.createByErrorMessage("用户没有该订单");
        }
        if(order.getStatus()>=Constant.OrderStatusEnum.PAID.getCode()){
            return ServerResponse.createBySuccess();
        }
        return ServerResponse.createByError();
    }
}
