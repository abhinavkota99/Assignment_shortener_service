package com.example.shortener.web;

import com.example.shortener.core.Link;

/** JSON representation of a link. Timestamps are ISO-8601 UTC strings. */
public record LinkResponse(String code, String shortUrl, String targetUrl, String createdAt, boolean custom) {

    static LinkResponse of(Link link, String shortUrl) {
        return new LinkResponse(link.code(), shortUrl, link.targetUrl(), link.createdAt().toString(), link.custom());
    }
}
