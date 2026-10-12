package com.smartcalc.app.data.request

import com.smartcalc.app.data.template.TemplateExample
import org.junit.Assert.*
import org.junit.Test

class TemplateExamplePromptTest {
    @Test fun separateImportedAnswerReachesTheExplicitTemplatePrompt() {
        assertEquals("Author solution\n\nОтвет: 2", exampleSolutionText(TemplateExample(solution = "Author solution", answer = "2")))
        assertEquals("Author solution", exampleSolutionText(TemplateExample(solution = "Author solution")))
        assertEquals("Ответ: 2", exampleSolutionText(TemplateExample(answer = "2")))
    }
}
