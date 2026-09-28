// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** JSON written as Python's json.dumps(..., separators=(",", ":")) writes it. */
class PyJsonTest {

    @Test
    void floatsAsPythonReprThem() {
        assertThat(PyJson.repr(1789983060.0907292)).isEqualTo("1789983060.0907292");
        assertThat(PyJson.repr(3000.0)).isEqualTo("3000.0");
        assertThat(PyJson.repr(0.006)).isEqualTo("0.006");
        assertThat(PyJson.repr(1e-5)).isEqualTo("1e-05");
        assertThat(PyJson.repr(1.5e16)).isEqualTo("1.5e+16");
        assertThat(PyJson.repr(1e22)).isEqualTo("1e+22");
        assertThat(PyJson.repr(-0.0)).isEqualTo("-0.0");
        assertThat(PyJson.repr(123456789012345.6)).isEqualTo("123456789012345.6");
    }

    @Test
    void compactWithIntegersKept() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", 7L);
        m.put("ts", 1789983042.0381064);
        m.put("list", List.of(1, 2.5, "x"));
        m.put("none", null);
        m.put("nan", Double.NaN);
        assertThat(PyJson.write(m)).isEqualTo("{\"id\":7,\"ts\":1789983042.0381064,\"list\":[1,2.5,\"x\"],\"none\":null,\"nan\":null}");
    }

    @Test
    void queryStringsAsPythonParsesThem() {
        var q = QueryString.parse("token=&tab=talk&x=a%20b+c&flag");
        assertThat(q).containsOnlyKeys("tab", "x");
        assertThat(q.get("x")).containsExactly("a b c");
        assertThat(QueryString.encode(q)).isEqualTo("tab=talk&x=a+b+c");
    }
}
