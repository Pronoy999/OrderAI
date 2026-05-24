package com.orderai.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class Order {
    private final String orderId;
    private final String orderDate;
    private final List<OrderItem> items;

    @JsonCreator
    public Order(
            @JsonProperty("orderId") String orderId,
            @JsonProperty("orderDate") String orderDate,
            @JsonProperty("items") List<OrderItem> items) {
        this.orderId = orderId;
        this.orderDate = orderDate;
        this.items = items;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getOrderDate() {
        return orderDate;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    @Override
    public String toString() {
        return "Order{" +
                "orderId='" + orderId + '\'' +
                ", orderDate='" + orderDate + '\'' +
                ", itemsCount=" + (items != null ? items.size() : 0) +
                '}';
    }
}
