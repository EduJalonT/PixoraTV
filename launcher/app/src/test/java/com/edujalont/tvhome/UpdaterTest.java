package com.edujalont.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class UpdaterTest {
    @Test
    public void comparesDateVersionsNumberByNumber() {
        assertTrue(Updater.compare("2026.11.06.002", "2026.11.06.001") > 0);
        assertTrue(Updater.compare("2026.10.29.004", "2026.11.06.001") < 0);
        assertTrue(Updater.compare("2026.11.10.001", "2026.11.9.999") > 0);
        assertEquals(0, Updater.compare("2026.11.06.001", "2026.11.06.001"));
        assertEquals(0, Updater.compare("2026.11.06", "2026.11.06.0"));
    }

    @Test
    public void highlightsTakeTheFirstListOnly() {
        String body = "Intro **bold**\n\n## Novedades desde X\n\n"
                + "- **Actualizador.** Busca la release en GitHub.\n"
                + "- Marcadores en vivo. Sigue hasta 10 ligas.\n"
                + "- Ver [docs](https://example.com) y `codigo`\n\n"
                + "## Instalar\n\n- no debe salir\n";
        List<String> h = Updater.highlights(body, 4);
        assertEquals(3, h.size());
        assertEquals("Actualizador.", h.get(0));
        assertEquals("Marcadores en vivo.", h.get(1));
        assertEquals("Ver docs y codigo", h.get(2));
        assertEquals(1, Updater.highlights(body, 1).size());
    }
}
