package xyz.tcheeric.cashu.mint.rest.entity.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.cashu.mint.rest.entity.MeltQuote;
import xyz.tcheeric.cashu.mint.rest.entity.repository.MeltQuoteRepository;

@RestController
@RequestMapping("/melt/quote")
@Deprecated
public class MeltQuoteController {

    @Autowired
    private MeltQuoteRepository repository;

    @PostMapping
    public ResponseEntity<MeltQuote> create(@RequestBody MeltQuote newMeltQuote) {
        var meltQuote = repository.save(newMeltQuote);
        return ResponseEntity.ok(meltQuote);
    }

    @GetMapping("/{quote}")
    public ResponseEntity<MeltQuote> getByQuote(@PathVariable("quote") String quote) {
        var meltQuote = repository.findByQuote(quote);
        return meltQuote.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
