package com.pipeline.api.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public class BuildingFootprintDTO {
   private Long bid;
   private Double jzgd;
   private Double baseZ;
   private String bldUsage;
   private Integer floors;
   private String wkt;
   private String diagnosticContext = "source context unavailable";
   private Map<String, Object> properties = new LinkedHashMap<>();

   public Map<String, Object> getProperties() {
      return this.properties;
   }

   public void setProperties(Map<String, Object> var1) {
      this.properties = new LinkedHashMap<>(var1);
   }

   public Long getBid() {
      return this.bid;
   }

   public void setBid(Long var1) {
      this.bid = var1;
   }

   public Double getJzgd() {
      return this.jzgd;
   }

   public void setJzgd(Double var1) {
      this.jzgd = var1;
   }

   public Double getBaseZ() {
      return this.baseZ;
   }

   public void setBaseZ(Double var1) {
      this.baseZ = var1;
   }

   public String getBldUsage() {
      return this.bldUsage;
   }

   public void setBldUsage(String var1) {
      this.bldUsage = var1;
   }

   public Integer getFloors() {
      return this.floors;
   }

   public void setFloors(Integer var1) {
      this.floors = var1;
   }

   public String getWkt() {
      return this.wkt;
   }

   public void setWkt(String var1) {
      this.wkt = var1;
   }

   public String getDiagnosticContext() {
      return this.diagnosticContext;
   }

   public void setDiagnosticContext(String diagnosticContext) {
      this.diagnosticContext = diagnosticContext == null ? "source context unavailable" : diagnosticContext;
   }
}
