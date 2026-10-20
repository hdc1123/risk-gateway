package com.risk.gateway.adapter;

import com.risk.gateway.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.MethodDescriptor;
import java.io.*;

/** 自定义 JSON marshaller，不是 protobuf wire format；服务端必须实现相同协议。 */
public final class AdapterProtocol {
    private AdapterProtocol() {}
    public record Request(int version, String idempotencyKey, String traceId, Book bookType, OrderRequest order) {}
    public record Reply(int version, String idempotencyKey, Book bookType, String status, String adapterOrderId) {}

    public static MethodDescriptor<Request, Reply> method(ObjectMapper mapper) {
        return MethodDescriptor.<Request, Reply>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("risk.adapter.v1.OrderRouter/Route")
            .setRequestMarshaller(marshaller(mapper, Request.class))
            .setResponseMarshaller(marshaller(mapper, Reply.class)).build();
    }

    static <T> MethodDescriptor.Marshaller<T> marshaller(ObjectMapper mapper, Class<T> type) {
        return new MethodDescriptor.Marshaller<>() {
            public InputStream stream(T value) {
                try { return new ByteArrayInputStream(mapper.writeValueAsBytes(value)); }
                catch (IOException e) { throw new IllegalArgumentException("Adapter JSON 编码失败", e); }
            }
            public T parse(InputStream stream) {
                try {
                    byte[] bytes = stream.readNBytes(65537);
                    if (bytes.length > 65536) throw new IOException("Adapter 消息过大");
                    return mapper.readValue(bytes, type);
                } catch (IOException e) { throw new IllegalArgumentException("Adapter JSON 解码失败", e); }
            }
        };
    }

    public static String businessKey(OrderRequest order) {
        // 订单ID由已校验请求提供，账户作用域用于跨请求/跨进程的 Adapter 持久幂等。
        return order.userId() + ":" + order.mt5Account() + ":" + order.orderId();
    }
}
