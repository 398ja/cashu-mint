"""Drive mint -> swap -> melt against an external mint using the Nutshell wallet.

Runs inside the `cashubtc/nutshell` container. Nutshell is a reference Cashu
implementation, so every message it sends is an independent reading of the NUT
specifications: whatever it rejects here, other wallets reject in production.

Takes the mint URL and an output-split mode. `wallet` lets Nutshell choose its
own mint output denominations, which is what a real wallet does. `canonical`
forces the single minimal-split denomination, which isolates the later legs from
a mint that only accepts canonical splits.

Reports one JSON object on stdout, prefixed by RESULT_JSON, describing how far
the flow travelled and why it stopped. Exit status is always 0 so the Java side
reports the protocol outcome rather than a process failure.
"""

import asyncio
import json
import secrets
import sys
import time
import traceback

from bolt11 import Bolt11, MilliSatoshi, Tag, TagChar, Tags, encode
from cashu.core.base import Method, Unit  # noqa: F401  (imported for side effects)
from cashu.wallet.wallet import Wallet

CANONICAL_SPLIT = "canonical"
MINT_AMOUNT = 64
SWAP_AMOUNT = 8
MELT_AMOUNT_MSAT = SWAP_AMOUNT * 1000


def build_melt_invoice() -> str:
    """Builds a genuinely valid BOLT11 request for the melt leg.

    A hand-copied invoice literal decays: the wallet rejects a malformed one in
    its own bech32 decoder, before any request reaches the mint, so a fixture
    typo masquerades as a melt failure. Encoding one here makes the string valid
    by construction. The dummy Lightning adapter never routes it; only its form
    has to be right.
    """
    tags = Tags(
        [
            Tag(TagChar.payment_hash, secrets.token_hex(32)),
            Tag(TagChar.payment_secret, secrets.token_hex(32)),
            Tag(TagChar.description, "cashu-mint interoperability melt"),
        ]
    )
    invoice = Bolt11("bc", int(time.time()), tags, MilliSatoshi(MELT_AMOUNT_MSAT))
    return encode(invoice, secrets.token_hex(32))


MELT_INVOICE = build_melt_invoice()


async def run(mint_url: str, split_mode: str, report: dict) -> None:
    report["stage"] = "load_mint"
    wallet = await Wallet.with_db(url=mint_url, db="/tmp/nutshell-interop-db")
    await wallet.load_mint()
    report["keysets"] = list(wallet.keysets.keys())

    report["stage"] = "mint_quote"
    quote = await wallet.request_mint(MINT_AMOUNT)
    report["mint_quote"] = quote.quote

    report["stage"] = "mint"
    outputs_split = [MINT_AMOUNT] if split_mode == CANONICAL_SPLIT else None
    minted = await wallet.mint(MINT_AMOUNT, quote.quote, split=outputs_split)
    report["minted_amounts"] = [p.amount for p in minted]

    report["stage"] = "swap"
    keep, send = await wallet.split(minted, SWAP_AMOUNT)
    report["swap_keep_amounts"] = [p.amount for p in keep]
    report["swap_send_amounts"] = [p.amount for p in send]

    report["stage"] = "melt_quote"
    melt_quote = await wallet.melt_quote(MELT_INVOICE)
    report["melt_quote"] = melt_quote.quote
    report["melt_fee_reserve"] = melt_quote.fee_reserve

    report["stage"] = "melt"
    # A wallet funds a melt for what the quote asks: the invoice amount plus the
    # reserve the mint quoted. Sending a fixed amount instead would report the
    # mint's correct insufficient_input rejection as an interoperability defect.
    melt_amount = melt_quote.amount + melt_quote.fee_reserve
    report["melt_amount"] = melt_amount
    _, to_melt = await wallet.split(keep + send, melt_amount)
    melted = await wallet.melt(
        to_melt, MELT_INVOICE, melt_quote.fee_reserve, melt_quote.quote
    )
    report["melt_state"] = str(melted.state)

    report["stage"] = "done"
    report["ok"] = True


def main() -> None:
    mint_url, split_mode = sys.argv[1], sys.argv[2]
    report: dict = {"stage": "startup", "ok": False, "split_mode": split_mode}
    try:
        asyncio.run(run(mint_url, split_mode, report))
    except Exception as failure:  # reported, never swallowed
        report["error"] = f"{type(failure).__name__}: {failure}"
        report["traceback"] = traceback.format_exc()[-4000:]
        # The mint's own error code is the finding; httpx hides it in the body.
        response = getattr(failure, "response", None)
        if response is not None:
            report["mint_response"] = response.text[:2000]
    print("RESULT_JSON " + json.dumps(report))


if __name__ == "__main__":
    main()
