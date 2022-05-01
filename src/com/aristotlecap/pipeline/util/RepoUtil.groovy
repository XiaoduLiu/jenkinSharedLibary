package com.aristotlecap.pipeline.util;

def clean(def name, boolean mixCaseRepo) {
    name = name.replaceAll(/\./, '-')
    return (mixCaseRepo) ? name.toLowerCase() : name
}
