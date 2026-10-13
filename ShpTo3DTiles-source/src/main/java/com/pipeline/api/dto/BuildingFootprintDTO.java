package com.pipeline.api.dto;

/** Values read from one building feature in the standalone SHP converter. */
public class BuildingFootprintDTO {
    private Long bid;
    private Double jzgd;
    private Double baseZ;
    private String bldUsage;
    private Integer floors;
    private String wkt;

    public Long getBid() { return bid; }
    public void setBid(Long bid) { this.bid = bid; }
    public Double getJzgd() { return jzgd; }
    public void setJzgd(Double jzgd) { this.jzgd = jzgd; }
    public Double getBaseZ() { return baseZ; }
    public void setBaseZ(Double baseZ) { this.baseZ = baseZ; }
    public String getBldUsage() { return bldUsage; }
    public void setBldUsage(String bldUsage) { this.bldUsage = bldUsage; }
    public Integer getFloors() { return floors; }
    public void setFloors(Integer floors) { this.floors = floors; }
    public String getWkt() { return wkt; }
    public void setWkt(String wkt) { this.wkt = wkt; }
}
