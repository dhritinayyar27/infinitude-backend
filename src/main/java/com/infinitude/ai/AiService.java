package com.infinitude.ai;

import com.infinitude.ai.model.TocAiResponse;
import com.infinitude.ai.model.SectionAiResponse;

public interface AiService {

    TocAiResponse generateTableOfContents(String topic, String difficulty, String apiKey, String model);

    SectionAiResponse generateSection(String prompt, String apiKey, String model);
}
