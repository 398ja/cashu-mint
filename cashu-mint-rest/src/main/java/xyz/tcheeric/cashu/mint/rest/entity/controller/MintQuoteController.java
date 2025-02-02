package xyz.tcheeric.cashu.mint.rest.entity.controller;

import xyz.tcheeric.cashu.mint.rest.entity.MintQuote;
import xyz.tcheeric.cashu.mint.rest.entity.repository.MintQuoteRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mint/quote")
@Deprecated
public class MintQuoteController {

    @Autowired
    private MintQuoteRepository repository;

    @PostMapping
    public ResponseEntity<MintQuote> create(@RequestBody MintQuote newMintQuote) {
        var mintQuote = repository.save(newMintQuote);
        return ResponseEntity.ok(mintQuote);
    }

    @GetMapping("/{quote}")
    public ResponseEntity<MintQuote> getByQuote(@PathVariable("quote") String quote) {
        var mintQuote = repository.findByQuote(quote);
        return mintQuote.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
