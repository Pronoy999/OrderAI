package com.orderai.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class OrderItem {
    private final String name;
    private final String quantity;
    private final double price;
    private final String category;

    @JsonCreator
    public OrderItem(
            @JsonProperty("name") String name,
            @JsonProperty("quantity") String quantity,
            @JsonProperty("price") double price,
            @JsonProperty("category") String category) {
        this.name = name;
        this.quantity = quantity;
        this.price = price;
        this.category = category;
    }

    public String getName() {
        return name;
    }

    public String getQuantity() {
        return quantity;
    }

    public double getPrice() {
        return price;
    }

    public String getCategory() {
        return category;
    }

    @Override
    public String toString() {
        return "OrderItem{" +
                "name='" + name + '\'' +
                ", quantity='" + quantity + '\'' +
                ", price=" + price +
                ", category='" + category + '\'' +
                '}';
    }
}
