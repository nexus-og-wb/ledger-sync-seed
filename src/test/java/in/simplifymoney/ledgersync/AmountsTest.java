package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.simplifymoney.ledgersync.parse.Amounts;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Amount extraction.
 *
 * This suite is green. It has been green since it was written.
 */
class AmountsTest {

    @Test
    void readsRupeesWithADot() {
        assertEquals(new BigDecimal("2499.50"),
                Amounts.first("Rs.2,499.50 debited from a/c **4821 on 04-07-26 at "
                        + "20:24 to AMAZON PAY. Avl Bal: Rs.89,032.61."));
    }

    @Test
    void readsInrPrefix() {
        assertEquals(new BigDecimal("333.33"),
                Amounts.first("Dear Customer, Acct XX9075 is debited with INR 333.33 "
                        + "on 04/07/2026 07:54. Info: SWIGGY. Avl Bal Rs.49,857.25"));
    }

    @Test
    void readsThousandsSeparators() {
        assertEquals(new BigDecimal("45000.00"),
                Amounts.first("Rs.45,000.00 credited to a/c **4821 on 01-07-26 at "
                        + "09:02 by SALARY CREDIT. Avl Bal: Rs.93,211.40"));
    }

    @Test
    void readsTheStatedBalance() {
        assertEquals(new BigDecimal("89032.61"),
                Amounts.statedBalance("Rs.2,499.50 debited from a/c **4821 on "
                        + "04-07-26 at 20:24 to AMAZON PAY. Avl Bal: Rs.89,032.61."));
    }

    @Test
    void readsWholeRupeeAmountWithoutDecimals() {
        assertEquals(new BigDecimal("5.00"),
                Amounts.first("Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to UPI/WATER CAN. Avl Bal: Rs.92,213.10."));
    }

    @Test
    void readsInrWithoutSpaceOrDecimals() {
        assertEquals(new BigDecimal("99.00"),
                Amounts.first("Sent INR99\nTo: UPI/VEGETABLE VENDOR\nOn: 26 Jul 26 09:47\nA/c: XX4821\nAvailable Balance: INR 39203.03"));
    }

    @Test
    void readsInrWithThousandsSeparatorNoDecimals() {
        assertEquals(new BigDecimal("4200.00"),
                Amounts.first("ICICI Bank Acct XX9075 Dr INR 4,200 on 27-Jul-2026 12:00; MYNTRA ref no 263970554183. BalAvl Rs 47,805.13"));
    }

    @Test
    void readsMultipleCommasIndianFormatting() {
        assertEquals(new BigDecimal("200000.00"),
                Amounts.first("Rs.2,00,000.00 debited from a/c **4821 on 04-07-26 at 07:19. Avl Bal: Rs.5,00,000.00."));
        assertEquals(new BigDecimal("200000.00"),
                Amounts.first("INR 2,00,000 debited from a/c **4821 on 04-07-26 at 07:19. Avl Bal: Rs.5,00,000.00."));
    }

    @Test
    void ignoresAMessageWithNoAmountAtAll() {
        assertEquals(null, Amounts.first("Your Swiggy order is on the way!"));
    }
}
