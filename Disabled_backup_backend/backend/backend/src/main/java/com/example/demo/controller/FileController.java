package com.example.demo.controller;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.MalformedURLException;

@RestController
@RequestMapping("/uploads")
public class FileController {

@GetMapping("/{filename}")
public ResponseEntity<Resource> getImage(@PathVariable String filename) throws MalformedURLException {

    Resource resource = new UrlResource("file:uploads/" + filename);

    return ResponseEntity.ok()
            .header("Content-Type", "image/png") // ⭐ 핵심
            .body(resource);
    }
}