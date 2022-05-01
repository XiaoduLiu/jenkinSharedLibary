package com.aristotlecap.pipeline.models

import com.cloudbees.groovy.cps.NonCPS

import com.aristotlecap.pipeline.models.Validation
import com.aristotlecap.pipeline.models.*

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

class HelmChart implements Serializable {

    static HelmChart fromJsonString(String text) {
        JsonSlurper parser = new JsonSlurper()
        Map json = parser.parseText(text)
        return new HelmChart(json.chartName, json.chartPath, json.chartOptions, json.environment, json.namespace, json.values)
    }

    static HelmChart fromMap(Map map) {
        return new HelmChart(map.chartName, map.chartPath, map.chartOptions, map.environment, map.namespace, map.values)
    }

    final String chartName;
    final String chartPath;
    final String chartOptions;
    final String environment;
    final String namespace;
    final String values

    HelmChart(String chartName, String chartPath, String chartOptions, String environment, String namespace, String values) {
        this.chartName = chartName
        this.chartPath = chartPath
        this.chartOptions = chartOptions
        this.environment = environment
        this.namespace = namespace
        this.values = values
    }

    @NonCPS
    public String toString() {
        String s
        return "{ chartName:" + this.chartName + "," +
               "  chartPath:" + this.chartPath + "," +
               "  chartOptions:" + this.chartOptions + "," +
               "  environment:" + this.environment + "," +
               "  namespace:" + this.namespace +
               "  values: " + this.values + "}"
    }

}
