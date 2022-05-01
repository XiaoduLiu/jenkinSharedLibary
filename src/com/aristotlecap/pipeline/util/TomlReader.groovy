package com.aristotlecap.pipeline.util

import java.util.regex.Matcher

String trimQuotes(String text) {
    if (text.size() > 2 && text.startsWith('"') && text.endsWith('"')) {
        return text[1..-2]
    }
    return text
}

Map read(String content) {
    List lines = content.split('\n').findAll { line -> !(line ==~ /^\s*$/) }
    Map map = [:]
    Map table = map
    for (String line : lines) {
        Matcher tableMatcher = line =~ /^\s*\[(.*)\]\s*$/
        if (tableMatcher.find()) {
            map[tableMatcher[0][1]] = [:]
            table = map[tableMatcher[0][1]]
        }
        Matcher kvMatcher = line =~ /^\s*(\w+)\s*=\s*([^\s]+)\s*$/
        Matcher arrayMatcher = line =~ /^\s*(\w+)\s*=\s*\[(.*)\]\s*$/
        if (kvMatcher.find()) {
            table[kvMatcher[0][1]] = trimQuotes(kvMatcher[0][2])
        } else if (arrayMatcher.find()) {
            table[arrayMatcher[0][1]] = arrayMatcher[0][2]
                .split(',')
                .collect { element -> trimQuotes(element) }
        }
    }
    return map
}

return this
