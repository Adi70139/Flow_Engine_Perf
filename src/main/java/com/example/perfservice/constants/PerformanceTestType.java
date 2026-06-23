package com.example.perfservice.constants;

public enum PerformanceTestType {
    LOAD,   // Fixed N users for X duration — models steady traffic
    STRESS, // Ramp users until error rate spikes — finds the breaking point
    SPIKE,  // Sudden burst then back to baseline — tests recovery
    SOAK    // Sustained load over long period — surfaces memory leaks / degradation
}