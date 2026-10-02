package com.infinitude.ai;

import com.infinitude.ai.model.TocAiResponse;

public interface AiService {

    TocAiResponse generateTableOfContents(String topic, String difficulty, String apiKey, String model);

}
