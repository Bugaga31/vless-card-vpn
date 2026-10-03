/* Local regression harness for the patched upstream cache parser; no network traffic. */
#include "byedpi/mpool.h"
#include <stdio.h>
#include <string.h>
#include <assert.h>
int main(void) {
    const char *cases[] = {
        "0 127.0.0.1 32 443 1790935200 example.org\n",
        "0 127.0.0.1\n",
        "0 127.0.0.1 -2147483648 443 1 -\n",
        "0 127.0.0.1 2147483647 443 1 -\n",
        "0 2001:db8:1234:5678:90ab:cdef:1234:5678 128 443 1790935200 -\n",
        "0 127.0.0.1 32 443 9223372036854775807 -\n",
        "garbage\n"
    };
    for (unsigned i = 0; i < sizeof(cases)/sizeof(cases[0]); i++) {
        struct mphdr *pool = mem_pool(MF_EXTRA, CMP_BITS);
        assert(pool);
        FILE *f = tmpfile(); assert(f);
        fputs(cases[i], f); rewind(f); load_cache(pool, f, NULL); fclose(f);
        mem_destroy(pool);
    }
    puts("CACHE_PARSE_PASS: full, truncated, invalid-prefix and IPv6 records under ASan/UBSan");
    return 0;
}
